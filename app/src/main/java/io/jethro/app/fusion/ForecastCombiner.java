package io.jethro.app.fusion;

import java.util.List;

/**
 * Combines a name's per-source forecasts into ONE (ADR-0055 phase 3) — the step that replaces N
 * subsystems deciding alone. A weighted average (weights from evidence — ADR-0055 phase-1 telemetry —
 * shrunk toward equal) times a **diversification multiplier**: averaging correlated forecasts shrinks
 * the scale, and the DM restores it, capped at 2.5 (Carver, Systematic Trading 2015). The result is
 * clamped back into {@code [-CAP, +CAP]}.
 *
 * <p>Pure and dimensionless — the combined value is a conviction, never a size or a price (ADR-0016 /
 * invariant 7); {@link TargetPlanner} turns it into a target position deterministically downstream.
 */
public final class ForecastCombiner {

    /** Carver's cap on the diversification multiplier. */
    public static final double MAX_DIVERSIFICATION_MULTIPLIER = 2.5;

    private ForecastCombiner() {
    }

    /** A forecast with the weight (source credibility) it enters the average at. */
    public record Weighted(Forecast forecast, double weight) {
    }

    /** The fused view: the combined forecast plus how many sources contributed and the DM applied. */
    public record Combined(String instrument, double value, int activeSources, double diversificationMultiplier) {
    }

    /**
     * @param assumedAvgCorrelation the assumed average pairwise correlation of the sources' forecasts,
     *   used only to size the diversification multiplier. A PLACEHOLDER (0..1) until forecast
     *   correlations are estimated — high ρ ⇒ little diversification benefit, low ρ ⇒ more.
     */
    public static Combined combine(String instrument, List<Weighted> forecasts, double assumedAvgCorrelation) {
        double weightSum = 0;
        double weighted = 0;
        int active = 0;
        for (Weighted w : forecasts) {
            if (w.weight() <= 0 || w.forecast() == null) {
                continue;
            }
            weightSum += w.weight();
            weighted += w.weight() * w.forecast().value();
            active++;
        }
        if (weightSum <= 0 || active == 0) {
            return new Combined(instrument, 0.0, 0, 1.0);
        }
        double average = weighted / weightSum;
        double dm = diversificationMultiplier(active, assumedAvgCorrelation);
        return new Combined(instrument, Forecast.clamp(average * dm), active, dm);
    }

    /**
     * Diversification multiplier for {@code n} equally-important forecasts with assumed average pairwise
     * correlation {@code rho}: {@code 1/sqrt(1/n + (n-1)/n · rho)}, capped. n=1 → 1; ρ=1 → 1 (no
     * diversification); the cap prevents an over-confident scale-up on many correlated inputs.
     */
    public static double diversificationMultiplier(int n, double rho) {
        if (n <= 1) {
            return 1.0;
        }
        double r = Math.max(0.0, Math.min(1.0, rho));
        double variance = 1.0 / n + ((double) (n - 1) / n) * r;
        if (!(variance > 0)) {
            return 1.0;
        }
        return Math.min(MAX_DIVERSIFICATION_MULTIPLIER, 1.0 / Math.sqrt(variance));
    }
}
