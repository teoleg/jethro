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
 * The ADR-0060 Phase 2 write path: turns a passing promotion verdict into a real, tradable
 * reference-data entry, and evicts the stalest discovered name when the discovered set is over its cap.
 * Pure orchestration over a {@link UniverseRefdataGateway} + audit log — no JDBC here, so it is unit
 * tested against an in-memory gateway.
 *
 * <p>A promoted name is a FIRST-CLASS instrument: it joins the sim/backtest universe at the next session
 * (so it ticks, gets indicators/signals, is OOS-evaluated) and trades through the SAME gates as every
 * other name (OOS backtest gate ADR-0049, pre-trade guardrail, sim-only + firm breaker) — no special
 * probation. This is deliberate: running discovered names through the real order/risk path is the whole
 * point (it validates the config, the risk limits and the strategy stack on live-discovered names).
 *
 * <ul>
 *   <li><b>Idempotent</b> — a name already in the master is never re-written (a restart re-promotes to a
 *       no-op).</li>
 *   <li><b>Provisional money dials, flagged</b> — adv/spread are stamped with a {@code PROVISIONAL}
 *       provenance (money-dial rule: never a silent default). They size/cost the name's orders until its
 *       ADV is measured from our own tape — an honest starting assumption, not a hidden invented rule.</li>
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
     * Write one promoted candidate as a first-class reference-data instrument. Idempotent: returns false
     * (no write, no audit row) if it already exists. Returns true when a new instrument was written.
     */
    public boolean promote(UniverseCandidate c, UniversePromotionPolicy.Verdict verdict,
                           String sessionEpoch, String feedMode, long now) {
        String id = c.instrumentId();
        if (refdata.exists(id)) {
            return false; // already tracked/promoted — nothing to do
        }
        String name = companies.displayName(id);
        String displayName = (name != null ? name : id) + " (discovered)";

        // Yahoo covers virtually every US ticker (delayed), so a discovered name gets live marks under a
        // live provider; under the SIM feed it joins the ticked sim universe at boot. We deliberately do
        // NOT write alpaca/finnhub symbology here — keeping it off the real-time WS subscribe set avoids
        // the free-tier symbol-limit churn; the Yahoo/sim path is enough to price and trade it.
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
        attributes.put("discovered_at", Long.toString(now));
        attributes.put("discovery_evidence",
                "score=%.1f days=%d sources=%s".formatted(c.score(), c.distinctDays(), String.join("/", c.sources())));

        refdata.writeMonitored(id, "EQUITY", "USD", BigDecimal.ONE, symbology, attributes);
        audit.record(now, sessionEpoch, feedMode, id, "PROMOTED", verdict.outcome().name(), c.score(),
                c.distinctDays(), String.join(",", c.sources()), verdict.reason(), false);
        refdata.refresh();
        log.info("ADR-0060 PROMOTED {} → tradable refdata (provisional adv/spread until measured); "
                + "joins the sim/OOS universe at the next session", id);
        return true;
    }

    /**
     * Enforce the cap (ADR-0060 §4): while the discovered set exceeds {@code maxMonitored}, evict the
     * LEAST-PERFORMING admissible name — lowest current discovery score, not pinned, no fills.
     * {@code scoreById} comes from the live candidate register; a name absent from it (no longer discussed)
     * scores 0 and is evicted first. Returns the evicted ids.
     */
    public List<String> enforceCap(Map<String, Double> scoreById, Set<String> pinList,
                                   String sessionEpoch, String feedMode, long now) {
        int cap = props.maxMonitoredOrDefault();
        List<String> evicted = new java.util.ArrayList<>();
        // Guard against an unbounded loop: at most one pass over the current discovered set.
        for (int guard = 0; guard < 1_000; guard++) {
            List<String> discovered = refdata.discoveredInstrumentIds();
            if (discovered.size() <= cap) {
                break;
            }
            String worst = null;
            double worstScore = Double.MAX_VALUE;
            for (String id : discovered) {
                if (pinList.contains(id) || refdata.hasFills(id)) {
                    continue; // pinned or has traded — never evict
                }
                // Least-performing = lowest current discovery score; a name that fell out of the register
                // (no longer discussed) scores 0 and is evicted first.
                double score = scoreById.getOrDefault(id, 0.0);
                if (score < worstScore) {
                    worstScore = score;
                    worst = id;
                }
            }
            if (worst == null) {
                break; // everything over the cap is pinned or has traded — cannot evict further
            }
            if (refdata.evict(worst)) {
                audit.record(now, sessionEpoch, feedMode, worst, "EVICTED", "EVICTED_LOW_SCORE", worstScore, null,
                        null, "evicted as lowest-scoring discovered name to stay within cap " + cap, false);
                evicted.add(worst);
                log.info("ADR-0060 EVICTED {} (lowest-scoring discovered name, score {}; cap {})",
                        worst, worstScore, cap);
            } else {
                break; // refused (not discovered) — stop
            }
        }
        if (!evicted.isEmpty()) {
            refdata.refresh();
        }
        return evicted;
    }

    /**
     * Clean cross-mode promotion leftovers (invariant 8 / ADR-0029): evict every DISCOVERED name in
     * {@code foreignPromoted} — names promoted while the process ran a DIFFERENT feed mode — so the current
     * mode's universe reflects the current mode's discovery only, never a SIM name persisting into LIVE.
     * Guarded EXACTLY like cap eviction: never a pinned name, never a name that has traded (a held position
     * is never orphaned) and, by construction, never a core (non-discovered) name. An evicted name is
     * re-promotable by discovery under the current mode if it still qualifies. Returns the evicted ids.
     */
    public List<String> evictForeignModePromotions(Set<String> foreignPromoted, Set<String> pinList,
                                                    String sessionEpoch, String feedMode, long now) {
        List<String> evicted = new java.util.ArrayList<>();
        for (String id : refdata.discoveredInstrumentIds()) {
            if (!foreignPromoted.contains(id)) {
                continue; // promoted under the current mode (or not a tracked promotion) — keep
            }
            if (pinList.contains(id) || refdata.hasFills(id)) {
                continue; // pinned or has traded — never evict (don't orphan a position/tape)
            }
            if (refdata.evict(id)) {
                audit.record(now, sessionEpoch, feedMode, id, "EVICTED", "EVICTED_FOREIGN_MODE", null, null,
                        null, "evicted cross-mode promotion so the " + feedMode + " universe reflects "
                                + feedMode + " discovery only (invariant 8 / ADR-0029); re-promotable under "
                                + feedMode, false);
                evicted.add(id);
                log.info("ADR-0060 EVICTED {} — promoted under a different feed mode; cleaned so the {} "
                        + "universe is {}-only (re-promotable under {})", id, feedMode, feedMode, feedMode);
            }
        }
        if (!evicted.isEmpty()) {
            refdata.refresh();
        }
        return evicted;
    }
}
