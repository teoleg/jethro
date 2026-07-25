package io.jethro.app.discovery;

import io.jethro.messaging.Provenance;
import io.jethro.trading.riskpnl.InstrumentRefSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * The ADR-0060 daily universe controller. On a daily cadence it runs the {@link UniversePromotionPolicy}
 * over the live discovery candidates and either records what it WOULD promote (dry-run) or actually writes
 * each passing name into reference data (becoming a first-class instrument) and evicts the stalest to stay
 * within the cap.
 *
 * <ul>
 *   <li><b>Dry-run</b> ({@code jethro.universe.dynamic.write=false}, the default): decides + audits
 *       PROPOSED rows, writes nothing to refdata.</li>
 *   <li><b>Write</b> ({@code write=true}): promotes via {@link UniversePromotionService} — the name joins
 *       the reference-data master and, from the next session, the trading universe; it trades through the
 *       same gates as any other name (OOS backtest, guardrail, sim-only + breaker).</li>
 * </ul>
 *
 * <p>Gated on {@code jethro.universe.dynamic.enabled} (default false). Off the tick path (MIN_PRIORITY).
 */
public final class UniversePromotionLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(UniversePromotionLifecycle.class);

    /** First evaluation shortly after boot so the proposals surface early; then every interval. */
    private static final long INITIAL_DELAY_SECONDS = 60;
    /** Don't re-persist a PROPOSED row for the same name more than once per ~20h (survives reboots). */
    private static final long PROPOSAL_DEDUPE_MILLIS = 20 * 3_600_000L;
    /** How many ranked candidates to consider each cycle — bounded work on the Pi. */
    private static final int CONSIDER = 200;

    /** A candidate paired with the gate's verdict — the unit both the UI and the audit consume. */
    public record Proposal(UniverseCandidate candidate, UniversePromotionPolicy.Verdict verdict) {
    }

    private static final long DAY_MILLIS = 86_400_000L;

    private final UniverseCandidates candidates;
    private final UniversePromotionEvaluator evaluator;
    private final InstrumentRefSource refs;
    private final CompanyDirectory companies;
    private final DynamicUniverseProperties props;
    private final ObjectProvider<UniversePromotionRepository> audit;
    private final ObjectProvider<UniversePromotionService> service;
    private ScheduledExecutorService scheduler;

    public UniversePromotionLifecycle(UniverseCandidates candidates, UniversePromotionEvaluator evaluator,
                                      InstrumentRefSource refs, CompanyDirectory companies,
                                      DynamicUniverseProperties props,
                                      ObjectProvider<UniversePromotionRepository> audit,
                                      ObjectProvider<UniversePromotionService> service) {
        this.candidates = candidates;
        this.evaluator = evaluator;
        this.refs = refs;
        this.companies = companies;
        this.props = props;
        this.audit = audit;
        this.service = service;
    }

    /**
     * Evaluate the current candidates against the gate, read-only. Pure w.r.t. the world (no writes) —
     * the proposals endpoint calls this directly, and the scheduled cycle calls it before acting. The
     * daily budget is seeded from the count of PROMOTED rows already written today, so it persists across
     * cycles and reboots (0 in dry-run, where nothing is promoted).
     */
    public List<Proposal> evaluateNow() {
        List<UniverseCandidate> ranked = candidates.ranked(CONSIDER);
        Set<String> tracked = refs.instrumentIds();
        List<UniversePromotionPolicy.Verdict> verdicts =
                evaluator.evaluateAll(ranked, tracked, props.blacklistOrEmpty(), companies::covers, promotedToday());
        List<Proposal> out = new java.util.ArrayList<>(ranked.size());
        for (int i = 0; i < ranked.size(); i++) {
            out.add(new Proposal(ranked.get(i), verdicts.get(i)));
        }
        return out;
    }

    /** Promotions already granted this UTC day — the persisted daily budget counter (0 in dry-run). */
    private int promotedToday() {
        UniversePromotionRepository repo = audit.getIfAvailable();
        if (repo == null || !props.writeEnabled()) {
            return 0;
        }
        long now = System.currentTimeMillis();
        return repo.promotedCountSince(now - (now % DAY_MILLIS));
    }

    @Override
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "universe-promotion");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        long interval = props.evaluationIntervalSecondsOrDefault();
        scheduler.scheduleWithFixedDelay(this::runOnce, INITIAL_DELAY_SECONDS, interval, TimeUnit.SECONDS);
        var t = props.toThresholds();
        log.info("ADR-0060 universe promotion gate started in {} mode: score>={}, sustained>={}d, sources>={}, "
                        + "budget={}/day, cap={}; first eval in {}s then every {}s",
                props.writeEnabled() ? "WRITE (promotes discovered names into refdata → tradable next session)"
                        : "DRY-RUN (writes nothing)",
                t.minScore(), t.minSustainedDays(), t.minSources(), t.maxPromotionsPerDay(),
                props.maxMonitoredOrDefault(), INITIAL_DELAY_SECONDS, interval);
    }

    private void runOnce() {
        try {
            long now = System.currentTimeMillis();
            List<Proposal> proposals = evaluateNow();
            List<Proposal> wouldPromote = proposals.stream().filter(p -> p.verdict().promote()).toList();
            UniversePromotionRepository repo = audit.getIfAvailable();
            UniversePromotionService svc = props.writeEnabled() ? service.getIfAvailable() : null;

            if (wouldPromote.isEmpty()) {
                log.info("ADR-0060 {}: {} candidate(s), none clear the gate today",
                        svc != null ? "write" : "dry-run", proposals.size());
            } else {
                String names = wouldPromote.stream().map(p -> p.candidate().instrumentId())
                        .collect(Collectors.joining(", "));
                log.info("ADR-0060 {}: {} candidate(s), {} clear the gate: {}",
                        svc != null ? "write" : "dry-run", proposals.size(), wouldPromote.size(), names);
            }

            if (svc != null) {
                // WRITE mode: actually promote each passing name (idempotent), then enforce the cap.
                for (Proposal p : wouldPromote) {
                    svc.promote(p.candidate(), p.verdict(), Provenance.epoch(), Provenance.mode().name(), now);
                }
                Map<String, Double> scores = new java.util.HashMap<>();
                for (Proposal p : proposals) {
                    scores.put(p.candidate().instrumentId(), p.candidate().score());
                }
                svc.enforceCap(scores, props.pinListOrEmpty(), Provenance.epoch(), Provenance.mode().name(), now);
                return;
            }

            // DRY-RUN mode: persist deduped PROPOSED rows only (nothing written to refdata).
            if (repo == null) {
                return; // persistence disabled — the log line above is the record
            }
            for (Proposal p : wouldPromote) {
                var c = p.candidate();
                if (repo.proposedSince(c.instrumentId(), now - PROPOSAL_DEDUPE_MILLIS)) {
                    continue; // already logged a proposal for this name recently — don't spam the audit
                }
                repo.record(now, Provenance.epoch(), Provenance.mode().name(), c.instrumentId(), "PROPOSED",
                        p.verdict().outcome().name(), c.score(), c.distinctDays(),
                        String.join(",", c.sources()), p.verdict().reason(), true);
            }
        } catch (Throwable t) {
            log.warn("ADR-0060 cycle failed: {}", t.toString());
        }
    }

    @Override
    public void stop() {
        var s = scheduler;
        if (s != null) {
            s.shutdownNow();
            scheduler = null;
        }
    }

    @Override
    public boolean isRunning() {
        return scheduler != null;
    }
}
