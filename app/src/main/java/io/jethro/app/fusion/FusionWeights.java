package io.jethro.app.fusion;

import java.util.Map;

/**
 * Per-source combination weights (ADR-0055). Phase 4 ships EQUAL weights — a stated placeholder, the
 * honest starting point before evidence accumulates (Oleg to revise). A later phase re-estimates these
 * from the phase-1 signal telemetry (hit-rate / realised edge per source), so a decayed source is
 * down-weighted by measurement, not decree; this seam keeps that swap a one-line change.
 */
public final class FusionWeights {

    private final Map<String, Double> weights;
    private final double defaultWeight;

    public FusionWeights(Map<String, Double> weights, double defaultWeight) {
        this.weights = weights == null ? Map.of() : Map.copyOf(weights);
        this.defaultWeight = defaultWeight;
    }

    /** Equal weight for every source — the phase-4 placeholder. */
    public static FusionWeights equal() {
        return new FusionWeights(Map.of(), 1.0);
    }

    public double weightFor(String source) {
        return weights.getOrDefault(source, defaultWeight);
    }
}
