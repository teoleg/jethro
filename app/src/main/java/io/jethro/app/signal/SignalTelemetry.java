package io.jethro.app.signal;

import io.jethro.domain.Side;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Per-signal health telemetry (ADR-0055 phase 1): records each source's live directional call and,
 * one horizon later, scores it by the realised forward return of following it. Rolling hit-rate and
 * average return per source are the evidence the fusion layer will weight sources by — decayed alphas
 * get down-weighted by measurement, not debate.
 *
 * <p>Strictly observational: it reads marks and writes its own table, and never touches an order, a
 * position, or a risk number (ADR-0016 / invariant 7). One open observation per (source, instrument)
 * at a time keeps volume bounded and answers the honest question — "is this source's CURRENT call on
 * this name working out?" Resolution runs off the request path on a cadence.
 */
public final class SignalTelemetry {

    private static final Logger log = LoggerFactory.getLogger(SignalTelemetry.class);

    /** Reads the current mark for an instrument (empty when unknown/stale). */
    public interface MarkSource {
        Optional<BigDecimal> markFor(String instrument);
    }

    private final SignalTelemetryStore store;
    private final MarkSource marks;
    /** The measurement ladder, longest rung first; {@code horizons.get(0)} is the desk's base horizon. */
    private final List<Integer> horizons;
    private final double flatThresholdBps;
    private final int rollingDays;
    private final int sampleLimit;
    private final long cohortWindowMillis;

    public SignalTelemetry(SignalTelemetryStore store, MarkSource marks, List<Integer> horizons,
                           double flatThresholdBps, int rollingDays, int sampleLimit,
                           int cohortWindowSeconds) {
        this.store = store;
        this.marks = marks;
        this.horizons = normaliseLadder(horizons);
        this.flatThresholdBps = flatThresholdBps;
        this.rollingDays = Math.max(1, rollingDays);
        this.sampleLimit = Math.max(1, sampleLimit);
        this.cohortWindowMillis = Math.max(0, cohortWindowSeconds) * 1000L;
    }

    /** Single-horizon telemetry — the pre-ADR-0082 shape, kept for callers with one horizon to measure. */
    public SignalTelemetry(SignalTelemetryStore store, MarkSource marks, int horizonSeconds,
                           double flatThresholdBps, int rollingDays, int sampleLimit,
                           int cohortWindowSeconds) {
        this(store, marks, List.of(horizonSeconds), flatThresholdBps, rollingDays, sampleLimit,
                cohortWindowSeconds);
    }

    /** Distinct, positive, longest-first — the order the ladder is reported and defaulted in. */
    private static List<Integer> normaliseLadder(List<Integer> raw) {
        var out = new java.util.TreeSet<Integer>(java.util.Comparator.reverseOrder());
        if (raw != null) {
            for (Integer h : raw) {
                if (h != null && h > 0) {
                    out.add(h);
                }
            }
        }
        return out.isEmpty() ? List.of(3600) : List.copyOf(out);
    }

    /** The horizons this telemetry measures every call over, longest first (ADR-0082). */
    public List<Integer> horizons() {
        return horizons;
    }

    /**
     * Records a source's directional call at every rung of the ladder, skipping any rung that already
     * has an open call on this name (ADR-0082).
     *
     * <p>All rungs share one entry instant and one entry mark — the same decision, graded over
     * different lengths of the future. That is what makes the rungs comparable: they differ only in how
     * long the desk is credited with having held the view, which is exactly the quantity being chosen
     * between. Cohort grouping is per rung, so each rung's independent sample is its own.
     */
    public void record(String source, String instrument, Side side, BigDecimal entryMark) {
        if (source == null || instrument == null || side == null || entryMark == null || entryMark.signum() <= 0) {
            return;
        }
        int direction = side == Side.BUY ? 1 : -1;
        Instant at = Instant.now();
        for (int horizonSeconds : horizons) {
            if (store.hasOpen(source, instrument, horizonSeconds)) {
                continue; // one open call per source+instrument+horizon — measure the CURRENT view
            }
            store.record(UUID.randomUUID().toString(), source, instrument, direction, entryMark,
                    at, horizonSeconds);
        }
    }

    /** Records a call, resolving the entry mark from the live cache (for callers without one to hand). */
    public void record(String source, String instrument, Side side) {
        if (instrument == null || side == null) {
            return;
        }
        marks.markFor(instrument).ifPresent(mark -> record(source, instrument, side, mark));
    }

    /** Resolves every due observation against the current mark. Off the request path (scheduled). */
    public int resolveDue() {
        List<SignalTelemetryStore.Open> due = store.due(Instant.now());
        int resolved = 0;
        Instant now = Instant.now();
        for (SignalTelemetryStore.Open o : due) {
            Optional<BigDecimal> mark = marks.markFor(o.instrument());
            if (mark.isEmpty() || mark.get().signum() <= 0) {
                continue; // no usable exit mark yet — leave it open, try again next sweep
            }
            double ret = SignalScoring.directionalReturn(o.direction(), o.entryMark(), mark.get());
            SignalScoring.Outcome outcome = SignalScoring.outcome(ret, flatThresholdBps);
            store.resolve(o.id(), mark.get(), ret, outcome.name(), now);
            resolved++;
        }
        if (resolved > 0) {
            log.debug("signal telemetry: resolved {} observation(s)", resolved);
        }
        return resolved;
    }

    /**
     * Rolling per-source health at EVERY rung of the ladder, longest rung first (ADR-0082) — the shape
     * the operator view and the loop report read, since which horizon a source's expectancy is real at
     * is itself the finding.
     */
    public List<SignalScoring.Stats> stats() {
        List<SignalScoring.Stats> out = new ArrayList<>();
        for (var rung : statsByHorizon().entrySet()) {
            out.addAll(rung.getValue());
        }
        return out;
    }

    /**
     * Rolling per-source health keyed by measurement horizon, longest rung first. Rungs with no
     * resolved history yet are still present with empty (zero-cohort) stats, which every consumer
     * already reads as "no evidence" — a rung that has not accrued is not a rung that passed.
     */
    public java.util.Map<Integer, List<SignalScoring.Stats>> statsByHorizon() {
        Instant since = Instant.now().minus(Duration.ofDays(rollingDays));
        List<String> sources = store.sources();
        var out = new java.util.LinkedHashMap<Integer, List<SignalScoring.Stats>>();
        for (int horizon : horizons) {
            List<SignalScoring.Stats> perSource = new ArrayList<>(sources.size());
            for (String source : sources) {
                List<SignalScoring.Observation> observations = new ArrayList<>();
                for (SignalTelemetryStore.Resolved r
                        : store.resolvedObservations(source, horizon, since, sampleLimit)) {
                    observations.add(new SignalScoring.Observation(
                            r.entryAt().toEpochMilli(), r.directionalReturn()));
                }
                perSource.add(SignalScoring.aggregate(source, observations, cohortWindowMillis,
                        flatThresholdBps, store.openCount(source, horizon)).atHorizon(horizon));
            }
            out.put(horizon, List.copyOf(perSource));
        }
        return out;
    }

    /** Convenience for callers holding a mark map rather than a live cache. */
    public static MarkSource fromMap(Function<String, Optional<BigDecimal>> lookup) {
        return lookup::apply;
    }
}
