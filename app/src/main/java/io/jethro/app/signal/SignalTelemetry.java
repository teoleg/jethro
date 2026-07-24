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
    private final int horizonSeconds;
    private final double flatThresholdBps;
    private final int rollingDays;
    private final int sampleLimit;

    public SignalTelemetry(SignalTelemetryStore store, MarkSource marks, int horizonSeconds,
                           double flatThresholdBps, int rollingDays, int sampleLimit) {
        this.store = store;
        this.marks = marks;
        this.horizonSeconds = Math.max(1, horizonSeconds);
        this.flatThresholdBps = flatThresholdBps;
        this.rollingDays = Math.max(1, rollingDays);
        this.sampleLimit = Math.max(1, sampleLimit);
    }

    /** Records a source's directional call, unless it already has one open on this name. */
    public void record(String source, String instrument, Side side, BigDecimal entryMark) {
        if (source == null || instrument == null || side == null || entryMark == null || entryMark.signum() <= 0) {
            return;
        }
        if (store.hasOpen(source, instrument)) {
            return; // one open call per source+instrument — bound volume, measure the current view
        }
        int direction = side == Side.BUY ? 1 : -1;
        store.record(UUID.randomUUID().toString(), source, instrument, direction, entryMark,
                Instant.now(), horizonSeconds);
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

    /** Rolling per-source health over the configured window. */
    public List<SignalScoring.Stats> stats() {
        Instant since = Instant.now().minus(Duration.ofDays(rollingDays));
        List<SignalScoring.Stats> out = new ArrayList<>();
        for (String source : store.sources()) {
            List<Double> returns = store.resolvedReturns(source, since, sampleLimit);
            out.add(SignalScoring.aggregate(source, returns, flatThresholdBps, store.openCount(source)));
        }
        return out;
    }

    /** Convenience for callers holding a mark map rather than a live cache. */
    public static MarkSource fromMap(Function<String, Optional<BigDecimal>> lookup) {
        return lookup::apply;
    }
}
