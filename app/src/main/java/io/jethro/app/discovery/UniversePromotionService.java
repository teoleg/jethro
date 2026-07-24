package io.jethro.app.discovery;

import io.jethro.refdata.RefDataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The ADR-0060 Phase 2 write path: turns a passing promotion verdict into an actual MONITOR_ONLY
 * reference-data entry, and evicts the stalest discovered name when the monitored set is over its cap.
 * Pure orchestration over a {@link UniverseRefdataGateway} + audit log — no JDBC here, so it is unit
 * tested against an in-memory gateway.
 *
 * <p>Conservatism the ADR mandates and this class enforces:
 * <ul>
 *   <li><b>Idempotent</b> — a name already in the master is never re-written (a restart re-promotes to a
 *       no-op).</li>
 *   <li><b>Monitor-only</b> — the written name carries {@code universe_status=MONITOR_ONLY}; it flows into
 *       marks/indicators/signals but the fusion order gate vetoes it, so it cannot trade until measured.</li>
 *   <li><b>Provisional money dials</b> — adv/spread are stamped with a {@code PROVISIONAL} provenance, never
 *       a silent default (money-dial rule); they gate nothing while monitor-only.</li>
 *   <li><b>Bounded</b> — eviction removes only {@code source=discovered} names (core is protected by
 *       construction), never a pinned name, never a name that has traded.</li>
 * </ul>
 */
public final class UniversePromotionService {

    private static final Logger log = LoggerFactory.getLogger(UniversePromotionService.class);

    private static final String PROVISIONAL = "PROVISIONAL — liquidity-tier default (ADR-0060), not measured";

    private final UniverseRefdataGateway refdata;
    private final CompanyDirectory companies;
    private final PromotionAudit audit;
    private final DynamicUniverseProperties props;

    public UniversePromotionService(UniverseRefdataGateway refdata, CompanyDirectory companies,
                                    PromotionAudit audit, DynamicUniverseProperties props) {
        this.refdata = refdata;
        this.companies = companies;
        this.audit = audit;
        this.props = props;
    }

    /**
     * Write one promoted candidate as a MONITOR_ONLY instrument. Idempotent: returns false (no write, no
     * audit row) if it already exists. Returns true when a new instrument was written.
     */
    public boolean promote(UniverseCandidate c, UniversePromotionPolicy.Verdict verdict,
                           String sessionEpoch, String feedMode, long now) {
        String id = c.instrumentId();
        if (refdata.exists(id)) {
            return false; // already tracked/promoted — nothing to do
        }
        String name = companies.displayName(id);
        String displayName = (name != null ? name : id) + " (discovered)";

        // Yahoo covers virtually every US ticker (delayed) — enough to MARK a monitored name at the next
        // session. We deliberately do NOT write alpaca/finnhub symbology here: keeping the discovered name
        // off the real-time WS subscribe set avoids the free-tier symbol-limit churn, and the delayed
        // Yahoo fallback is sufficient for a name that cannot trade anyway.
        Map<String, String> symbology = new LinkedHashMap<>();
        symbology.put("yahoo", id);
        symbology.put("discovered", id); // marker source; ignored by every market adapter

        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("display_name", displayName);
        attributes.put("adv_usd", props.provisionalAdvUsdOrDefault().toPlainString());
        attributes.put("adv_provenance", PROVISIONAL);
        attributes.put("spread_bps", props.provisionalSpreadBpsOrDefault().toPlainString());
        attributes.put("spread_provenance", PROVISIONAL);
        attributes.put(RefDataRepository.ATTR_SOURCE, RefDataRepository.SOURCE_DISCOVERED);
        attributes.put(RefDataRepository.ATTR_UNIVERSE_STATUS, RefDataRepository.STATUS_MONITOR_ONLY);
        attributes.put("discovered_at", Long.toString(now));
        attributes.put("discovery_evidence",
                "score=%.1f days=%d sources=%s".formatted(c.score(), c.distinctDays(), String.join("/", c.sources())));

        refdata.writeMonitored(id, "EQUITY", "USD", BigDecimal.ONE, symbology, attributes);
        audit.record(now, sessionEpoch, feedMode, id, "PROMOTED", verdict.outcome().name(), c.score(),
                c.distinctDays(), String.join(",", c.sources()), verdict.reason(), false);
        refdata.refresh();
        log.info("ADR-0060 PROMOTED {} → MONITOR_ONLY refdata (provisional adv/spread; cannot trade until measured)", id);
        return true;
    }

    /**
     * Enforce the monitored cap (ADR-0060 §4): while the discovered set exceeds {@code maxMonitored},
     * evict the STALEST admissible name — least-recent mention, not pinned, no fills. {@code lastSeenById}
     * comes from the live candidate register (a name absent from it is treated as maximally stale).
     * Returns the evicted ids.
     */
    public List<String> enforceCap(Map<String, Long> lastSeenById, Set<String> pinList,
                                   String sessionEpoch, String feedMode, long now) {
        int cap = props.maxMonitoredOrDefault();
        List<String> evicted = new java.util.ArrayList<>();
        // Guard against an unbounded loop: at most one pass over the current discovered set.
        for (int guard = 0; guard < 1_000; guard++) {
            List<String> discovered = refdata.discoveredInstrumentIds();
            if (discovered.size() <= cap) {
                break;
            }
            String stalest = null;
            long stalestSeen = Long.MAX_VALUE;
            for (String id : discovered) {
                if (pinList.contains(id) || refdata.hasFills(id)) {
                    continue; // pinned or has traded — never evict
                }
                long seen = lastSeenById.getOrDefault(id, 0L); // absent from register → maximally stale
                if (seen < stalestSeen) {
                    stalestSeen = seen;
                    stalest = id;
                }
            }
            if (stalest == null) {
                break; // everything over the cap is pinned or has traded — cannot evict further
            }
            if (refdata.evict(stalest)) {
                audit.record(now, sessionEpoch, feedMode, stalest, "EVICTED", "EVICTED_STALE", null, null, null,
                        "evicted as stalest discovered name to stay within cap " + cap, false);
                evicted.add(stalest);
                log.info("ADR-0060 EVICTED {} (stalest discovered name; cap {})", stalest, cap);
            } else {
                break; // refused (not discovered) — stop
            }
        }
        if (!evicted.isEmpty()) {
            refdata.refresh();
        }
        return evicted;
    }
}
