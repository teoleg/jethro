package io.jethro.app.fusion;

import io.jethro.app.signal.SignalScoring;

import java.util.List;
import java.util.Map;

/**
 * Per-source combination weights (ADR-0055). By default these are now EVIDENCE-BASED, re-estimated from
 * the phase-1 signal telemetry (hit-rate / realised edge per source) via {@link TelemetryWeights}, so a
 * decayed source is down-weighted by measurement, not decree. {@link #equal()} remains as the honest
 * cold-start / fallback (and telemetry itself resolves to equal until evidence accrues). A source with
 * no telemetry entry (e.g. the learned signal, which has its own walk-forward gate) takes the neutral
 * {@code defaultWeight}. Dimensionless — a conviction weight, never a size or a price (invariant 7).
 */
public final class FusionWeights {

    private final Map<String, Double> weights;
    private final double defaultWeight;

    public FusionWeights(Map<String, Double> weights, double defaultWeight) {
        this.weights = weights == null ? Map.of() : Map.copyOf(weights);
        this.defaultWeight = defaultWeight;
    }

    /** Equal weight for every source — the honest cold-start / fallback (a source not in the map
     *  takes the neutral 1.0 default). */
    public static FusionWeights equal() {
        return new FusionWeights(Map.of(), 1.0);
    }

    /** Evidence-based weights from phase-1 telemetry; sources absent from telemetry take the 1.0 default. */
    public static FusionWeights fromTelemetry(List<SignalScoring.Stats> stats, TelemetryWeights.Params params) {
        return new FusionWeights(TelemetryWeights.compute(stats, params), 1.0);
    }

    public double weightFor(String source) {
        return weights.getOrDefault(source, defaultWeight);
    }

    /** The resolved per-source weights (empty ⇒ all at the default) — for the UI/telemetry surface. */
    public Map<String, Double> snapshot() {
        return weights;
    }

    public double defaultWeight() {
        return defaultWeight;
    }
}
