package io.jethro.app.discovery;

import io.jethro.messaging.Provenance;
import io.jethro.trading.riskpnl.InstrumentRefSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * The ADR-0060 daily universe controller — <b>Phase 1: dry-run proposer only</b>. On a daily cadence it
 * runs the {@link UniversePromotionPolicy} over the live discovery candidates and records what it WOULD
 * promote, but writes <b>nothing</b> to reference data and places no order. This lets the gate be watched
 * against real candidates (via {@code /api/universe/proposals} and the durable {@code universe_promotion}
 * audit) before the risky refdata write path (Phase 2) is wired.
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

    private final UniverseCandidates candidates;
    private final UniversePromotionEvaluator evaluator;
    private final InstrumentRefSource refs;
    private final CompanyDirectory companies;
    private final DynamicUniverseProperties props;
    private final ObjectProvider<UniversePromotionRepository> audit;
    private ScheduledExecutorService scheduler;

    public UniversePromotionLifecycle(UniverseCandidates candidates, UniversePromotionEvaluator evaluator,
                                      InstrumentRefSource refs, CompanyDirectory companies,
                                      DynamicUniverseProperties props,
                                      ObjectProvider<UniversePromotionRepository> audit) {
        this.candidates = candidates;
        this.evaluator = evaluator;
        this.refs = refs;
        this.companies = companies;
        this.props = props;
        this.audit = audit;
    }

    /**
     * Evaluate the current candidates against the gate, read-only. Pure w.r.t. the world (no writes) —
     * the proposals endpoint calls this directly, and the scheduled cycle calls it before persisting.
     */
    public List<Proposal> evaluateNow() {
        List<UniverseCandidate> ranked = candidates.ranked(CONSIDER);
        Set<String> tracked = refs.instrumentIds();
        // Phase 1 dry-run: no real promotions exist yet, so the daily budget starts unused and the
        // evaluator threads it down the ranking to show which names it would spend on.
        List<UniversePromotionPolicy.Verdict> verdicts =
                evaluator.evaluateAll(ranked, tracked, props.blacklistOrEmpty(), companies::covers, 0);
        List<Proposal> out = new java.util.ArrayList<>(ranked.size());
        for (int i = 0; i < ranked.size(); i++) {
            out.add(new Proposal(ranked.get(i), verdicts.get(i)));
        }
        return out;
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
        log.info("ADR-0060 universe promotion gate started in DRY-RUN (nothing is written to refdata): "
                        + "score>={}, sustained>={}d, sources>={}, budget={}/day, cap={}; first eval in {}s then every {}s",
                t.minScore(), t.minSustainedDays(), t.minSources(), t.maxPromotionsPerDay(),
                props.maxMonitoredOrDefault(), INITIAL_DELAY_SECONDS, interval);
    }

    private void runOnce() {
        try {
            long now = System.currentTimeMillis();
            List<Proposal> proposals = evaluateNow();
            List<Proposal> wouldPromote = proposals.stream().filter(p -> p.verdict().promote()).toList();
            if (wouldPromote.isEmpty()) {
                log.info("ADR-0060 dry-run: {} candidate(s), none clear the gate today", proposals.size());
                return;
            }
            String names = wouldPromote.stream().map(p -> p.candidate().instrumentId())
                    .collect(Collectors.joining(", "));
            log.info("ADR-0060 dry-run: {} candidate(s), {} WOULD be promoted (not written): {}",
                    proposals.size(), wouldPromote.size(), names);
            UniversePromotionRepository repo = audit.getIfAvailable();
            if (repo == null) {
                return; // persistence disabled (DB-less run) — the log line above is the record
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
            log.warn("ADR-0060 dry-run cycle failed: {}", t.toString());
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
