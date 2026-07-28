package io.jethro.app.fusion;

import java.util.List;

/**
 * Combines a name's per-source forecasts into ONE (ADR-0055 phase 3) — the step that replaces N
 * subsystems deciding alone. A weighted average (weights from evidence — ADR-0055 phase-1 telemetry —
 * shrunk toward equal) times a **diversification multiplier**: averaging correlated forecasts shrinks
 * the scale, and the DM restores it, capped at 2.5 (Carver, Systematic Trading 2015). The result is
 * clamped back into {@code [-CAP, +CAP]}.
 *
 * <p><b>The DM is measured on the weights actually used, not on the source count (ADR-0076).</b> The
 * multiplier exists to undo the variance the averaging step removed, so it must be computed from the
 * variance of the average the desk actually formed. For normalised weights {@code w} under the assumed
 * equicorrelation {@code ρ}:
 * <pre>
 *   Var(Σ wᵢfᵢ)/σ² = Σwᵢ² + ρ·(1 − Σwᵢ²)        // the Σᵢ≠ⱼ wᵢwⱼ cross terms sum to 1 − Σwᵢ²
 *   DM             = 1/√(Σwᵢ² + ρ(1 − Σwᵢ²))
 * </pre>
 * {@code 1/Σwᵢ²} is the inverse-Herfindahl <em>effective</em> number of sources, so this is exactly the
 * count formula evaluated at the breadth the weights actually deliver — and it collapses to the count
 * formula identically when the weights are equal (Σwᵢ² = 1/n), which is the cold start and
 * {@code weights.mode=equal}. By Cauchy–Schwarz Σwᵢ² ≥ 1/n, and the denominator is increasing in Σwᵢ²
 * for ρ &lt; 1, so the weighted DM is never LARGER than the count DM: re-weighting can shrink the book,
 * never grow it.
 *
 * <p>Why that matters for money: weights are bounded on {@code [0.25, 3.0]} (a 12× span), so when the
 * telemetry down-weights a source to the floor for measured-negative expectancy, that source still
 * bought the desk a full extra unit of diversification leverage under the count rule — the emptier the
 * evidence, the more concentrated the weights, and the more the count over-levered. One source holding
 * 91% of the weight is one view, not two, and restoring √2 of scale for it is leverage no measurement
 * supports. This replaces the ADR-0067 property "re-weighting cannot scale the book" with the strictly
 * safer "re-weighting cannot GROW the book"; equal weights remain the maximum.
 *
 * <p><b>Then the sources' AGREEMENT scales it back down (ADR-0119).</b> The weighted average is the
 * desk's NET view; {@code Σwᵢ|fᵢ|/Σwᵢ} is its GROSS view, the conviction actually on the table. Their
 * ratio
 * <pre>
 *   agreement = |Σ wᵢ fᵢ| / Σ wᵢ |fᵢ|     ∈ [0, 1]
 * </pre>
 * is the fraction of the sources' total conviction that survives as net displacement rather than
 * cancelling — the same efficiency ratio the desk already uses over TIME for a trend (ADR-0113) and for
 * the hedge's own path (ADR-0100), read here ACROSS sources. It multiplies the combined value.
 *
 * <p>Why: sizing reads only the posterior MEAN, and two sources fighting to a small residual produce
 * the same mean as two quiet sources agreeing on it — while carrying far more uncertainty about the
 * sign. Averaging shrinks the mean; nothing was widening the uncertainty, so the desk took a full-
 * conviction position on a number that was the difference of two large opposing estimates. Worse, the
 * DM above then MULTIPLIED that residual up, on a diversification assumption the disagreement itself
 * contradicts.
 *
 * <p>Strictly one-way, by the triangle inequality: {@code agreement ≤ 1} always, so the combined value
 * can only ever SHRINK, and {@code agreement = 1} EXACTLY whenever every contributing forecast shares a
 * sign (or only one contributes) — so a name whose sources agree is byte-identical to before. The sign
 * is never touched. This is the third rule in the same family as ADR-0076 (re-weighting cannot grow the
 * book) and ADR-0098 (churn shrinkage is one-way).
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

    /** The fused view: the combined forecast plus how many sources contributed, the DM applied, and
     *  the ADR-0119 agreement scalar those sources earned (1 = unanimous sign, 0 = perfect
     *  cancellation). */
    public record Combined(String instrument, double value, int activeSources,
                           double diversificationMultiplier, double agreement) {
    }

    /**
     * @param assumedAvgCorrelation the assumed average pairwise correlation of the sources' forecasts,
     *   used only to size the diversification multiplier. A PLACEHOLDER (0..1) until forecast
     *   correlations are estimated — high ρ ⇒ little diversification benefit, low ρ ⇒ more.
     */
    public static Combined combine(String instrument, List<Weighted> forecasts, double assumedAvgCorrelation) {
        double weightSum = 0;
        double weighted = 0;
        double weightedGross = 0;
        double weightSqSum = 0;
        int active = 0;
        for (Weighted w : forecasts) {
            if (w.weight() <= 0 || w.forecast() == null) {
                continue;
            }
            weightSum += w.weight();
            weighted += w.weight() * w.forecast().value();
            weightedGross += w.weight() * Math.abs(w.forecast().value());
            weightSqSum += w.weight() * w.weight();
            active++;
        }
        if (weightSum <= 0 || active == 0) {
            return new Combined(instrument, 0.0, 0, 1.0, 1.0);
        }
        double average = weighted / weightSum;
        // Σwᵢ² over NORMALISED weights: Σ(wᵢ/Σw)² = Σwᵢ²/(Σw)². Formed from the running sums so the
        // weight vector is never materialised (ADR-0076).
        double normalisedSumSq = weightSqSum / (weightSum * weightSum);
        double dm = diversificationMultiplierForConcentration(normalisedSumSq, assumedAvgCorrelation);
        double agreement = agreement(weighted, weightedGross);
        return new Combined(instrument, Forecast.clamp(average * dm * agreement), active, dm, agreement);
    }

    /**
     * ADR-0119 — the share of the sources' gross conviction that survives as a net view:
     * {@code |Σwᵢfᵢ| / Σwᵢ|fᵢ|}. The Σw normalisation is common to both sums and cancels, so this is
     * formed from the same running sums the average is.
     *
     * <p>By the triangle inequality {@code |Σwᵢfᵢ| ≤ Σwᵢ|fᵢ|}, with EQUALITY exactly when every
     * contributing forecast shares a sign — so unanimous sources (and the single-source case) return 1
     * and change nothing, and the result never exceeds 1. All forecasts zero is the 0/0 case: the net
     * view is already zero, so the scalar is irrelevant and 1 is returned rather than a NaN. The
     * min/max clamp is defensive against floating-point residue only.
     */
    private static double agreement(double weightedNet, double weightedGross) {
        if (!(weightedGross > 0)) {
            return 1.0;
        }
        return Math.max(0.0, Math.min(1.0, Math.abs(weightedNet) / weightedGross));
    }

    /**
     * Diversification multiplier for {@code n} equally-important forecasts with assumed average pairwise
     * correlation {@code rho}: {@code 1/sqrt(1/n + (n-1)/n · rho)}, capped. n=1 → 1; ρ=1 → 1 (no
     * diversification); the cap prevents an over-confident scale-up on many correlated inputs.
     *
     * <p>Expressed through the weighted form at equal weights (Σwᵢ² = 1/n) so the two can never drift
     * apart: the count rule IS the ADR-0076 rule at its most favourable weight vector.
     */
    public static double diversificationMultiplier(int n, double rho) {
        if (n <= 1) {
            return 1.0;
        }
        return diversificationMultiplierForConcentration(1.0 / n, rho);
    }

    /**
     * Diversification multiplier from the concentration of the weights actually used (ADR-0076):
     * {@code 1/sqrt(sumSqNormalisedWeights + rho·(1 − sumSqNormalisedWeights))}, capped.
     *
     * <p>{@code sumSqNormalisedWeights} = Σwᵢ² over weights summing to 1, i.e. the Herfindahl index of
     * the weight vector; its reciprocal is the effective number of sources. It lies in {@code (0, 1]}:
     * 1 means all the weight sits on one source (⇒ DM 1, no diversification claimed), {@code 1/n} means
     * n equal sources (the maximum diversification n views can earn). Monotonicity: the denominator is
     * increasing in Σwᵢ² for ρ &lt; 1, so a more concentrated weight vector always yields a SMALLER
     * multiplier, and DM ≥ 1 always since Σwᵢ² ≤ 1.
     */
    public static double diversificationMultiplierForConcentration(double sumSqNormalisedWeights, double rho) {
        double h = Math.max(0.0, Math.min(1.0, sumSqNormalisedWeights));
        double r = Math.max(0.0, Math.min(1.0, rho));
        double variance = h + (1.0 - h) * r;
        if (!(variance > 0)) {
            return 1.0;
        }
        return Math.min(MAX_DIVERSIFICATION_MULTIPLIER, 1.0 / Math.sqrt(variance));
    }
}
