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
 * <p><b>Then the sources' AGREEMENT scales it back down (ADR-0119, restated by ADR-0124).</b> The
 * weighted average is the desk's estimate of the name's conviction; the sources' DISPERSION about that
 * average is how uncertain that estimate is. The scalar is the share of the combined view's total scale
 * that is the view rather than the disagreement around it:
 * <pre>
 *   s²        = Σŵᵢ(fᵢ − μ̂)² / (1 − Σŵᵢ²)         // unbiased weighted variance (Bessel/Kish)
 *   agreement = |μ̂| / √(μ̂² + s²) = 1/√(1 + (s/μ̂)²)  ∈ [0, 1]
 * </pre>
 * a monotone-decreasing function of the sources' coefficient of variation — the amplitude form of the
 * reliability ratio that attenuates a coefficient measured with error. It multiplies the combined value.
 *
 * <p>Why: sizing reads only the posterior MEAN, and two sources fighting to a small residual produce
 * the same mean as two quiet sources agreeing on it — while carrying far more uncertainty about the
 * sign. Averaging shrinks the mean; nothing was widening the uncertainty, so the desk took a full-
 * conviction position on a number that was the difference of two large opposing estimates. Worse, the
 * DM above then MULTIPLIED that residual up, on a diversification assumption the disagreement itself
 * contradicts.
 *
 * <p><b>Why the dispersion and not ADR-0119's sign ratio {@code |Σwᵢfᵢ|/Σwᵢ|fᵢ|} (ADR-0124).</b> That
 * ratio is 1 by construction when only ONE source contributes — there is nothing for it to disagree
 * with — so an uncorroborated view earned FULL conviction and, being unopposed, routinely carried the
 * LARGEST combined forecast in the cross-section while genuinely corroborated names were discounted
 * below it. The scalar was inverted in the breadth it was meant to reward. The dispersion form has no
 * such degenerate case: {@code 1 − Σŵᵢ²} is the residual degrees of freedom of the weighted variance,
 * which is ZERO at one effective source — the dispersion is UNESTIMABLE, which is not the same as zero,
 * and the honest scalar there is 0, not 1. It is also strictly smoother: a source's magnitude, not just
 * its sign, moves the scalar, so a name cannot flip between full size and flat on one sensor's noise.
 *
 * <p>Strictly one-way: {@code s² ≥ 0} so {@code agreement ≤ 1} always and the combined value can only
 * ever SHRINK; {@code agreement = 1} exactly when the contributing forecasts are identical. The sign is
 * never touched. This is the third rule in the same family as ADR-0076 (re-weighting cannot grow the
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

    /** The fused view: the combined forecast plus how many sources contributed, the DM applied, the
     *  ADR-0119/0124 agreement scalar those sources earned (1 = identical forecasts, 0 = the
     *  disagreement between them swamps the view, or there is only one effective source and so no
     *  corroboration was ever tested), and whether that scalar was MEASURED or merely unestimable.
     *
     *  <p>{@code estimable} is false in exactly one case (ADR-0135): sources spoke, but all the weight
     *  sits on ONE of them, so the residual degrees of freedom of the weighted variance are zero and the
     *  dispersion cannot be estimated at all. {@link #value} is then 0 — not because the desk formed a
     *  view of flat, but because it could form no view whose uncertainty it can measure. Those are
     *  different statements and downstream sizing must not conflate them. It is true everywhere else,
     *  including when NO source spoke (there is no view for the dispersion to be unknown about — ADR-0065
     *  owns that case and still sweeps the name to flat) and when two or more sources genuinely net to
     *  zero (a measured view of flat, which still exits). */
    public record Combined(String instrument, double value, int activeSources,
                           double diversificationMultiplier, double agreement, boolean estimable) {
    }

    /**
     * @param assumedAvgCorrelation the assumed average pairwise correlation of the sources' forecasts,
     *   used only to size the diversification multiplier. A PLACEHOLDER (0..1) until forecast
     *   correlations are estimated — high ρ ⇒ little diversification benefit, low ρ ⇒ more.
     */
    public static Combined combine(String instrument, List<Weighted> forecasts, double assumedAvgCorrelation) {
        double weightSum = 0;
        double weighted = 0;
        double weightSqSum = 0;
        int active = 0;
        for (Weighted w : forecasts) {
            if (w.weight() <= 0 || w.forecast() == null) {
                continue;
            }
            weightSum += w.weight();
            weighted += w.weight() * w.forecast().value();
            weightSqSum += w.weight() * w.weight();
            active++;
        }
        if (weightSum <= 0 || active == 0) {
            // No source spoke. There is no view here at all, so there is nothing whose uncertainty is
            // unknown: ADR-0065's orphan sweep owns this case and still works the name down to flat.
            return new Combined(instrument, 0.0, 0, 1.0, 0.0, true);
        }
        double average = weighted / weightSum;
        // Σwᵢ² over NORMALISED weights: Σ(wᵢ/Σw)² = Σwᵢ²/(Σw)². Formed from the running sums so the
        // weight vector is never materialised (ADR-0076).
        double normalisedSumSq = weightSqSum / (weightSum * weightSum);
        double dm = diversificationMultiplierForConcentration(normalisedSumSq, assumedAvgCorrelation);
        // Second pass for the dispersion ABOUT the average, which the first pass cannot know yet
        // (ADR-0124). Bounded by the source count (≤ 5 today) and allocation-free, like the first.
        double weightedSqDeviation = 0;
        for (Weighted w : forecasts) {
            if (w.weight() <= 0 || w.forecast() == null) {
                continue;
            }
            double deviation = w.forecast().value() - average;
            weightedSqDeviation += w.weight() * deviation * deviation;
        }
        double agreement = agreement(average, weightedSqDeviation / weightSum, normalisedSumSq);
        // ADR-0135: the SAME residual-degrees-of-freedom test the agreement scalar makes, surfaced. When
        // 1 − Σŵᵢ² is not positive the dispersion is unestimable rather than zero, so the 0 this hands
        // back is an absence of measurable conviction, not a measured conviction of flat.
        boolean estimable = 1.0 - normalisedSumSq > 0;
        return new Combined(instrument, Forecast.clamp(average * dm * agreement), active, dm, agreement,
                estimable);
    }

    /**
     * ADR-0124 — the share of the combined view's total scale that is the view rather than the
     * disagreement around it: {@code |μ̂| / √(μ̂² + s²)}, where {@code s²} is the sources' UNBIASED
     * weighted variance about {@code μ̂}. Equivalently {@code 1/√(1 + (s/μ̂)²)}: a monotone-decreasing
     * function of the sources' coefficient of variation, in amplitude units because it multiplies a
     * forecast (an amplitude), not a variance.
     *
     * <p>The Bessel/Kish correction for reliability weights divides the weighted mean square deviation
     * by the residual degrees of freedom {@code 1 − Σŵᵢ²}, which at equal weights is {@code (n−1)/n} and
     * so reproduces {@code Σ(fᵢ − μ̂)²/(n−1)} exactly. Its reciprocal {@code 1/Σŵᵢ²} is Kish's effective
     * number of sources, so the correction is stated in the same effective breadth the ADR-0076 DM is.
     *
     * <p>The three degenerate cases, each answered conservatively:
     * <ul>
     *   <li><b>No net view</b> ({@code μ̂ = 0}) — the combined value is already zero; return 0 rather
     *       than a 0/0 NaN.</li>
     *   <li><b>One effective source</b> ({@code Σŵᵢ² ≥ 1}, i.e. all the weight on one forecast) — the
     *       residual degrees of freedom are ZERO, so the dispersion is UNESTIMABLE. Return 0: an
     *       untested view is not a corroborated one. This is the ADR-0119 defect it repairs — the sign
     *       ratio returned 1 here, handing full conviction to exactly the names with no second opinion.
     *       It is self-healing, not a ban: the name sizes again as soon as a second sensor warms.</li>
     *   <li><b>Identical forecasts</b> ({@code s² = 0} with two or more effective sources) — full
     *       corroboration, return 1, and the name is byte-identical to the pre-ADR-0124 desk.</li>
     * </ul>
     * The min/max clamp is defensive against floating-point residue only; {@code s² ≥ 0} already bounds
     * the result at 1, so the scalar is strictly one-way and can never grow the book.
     *
     * @param mean               {@code μ̂}, the weighted average of the contributing forecasts
     * @param meanSqDeviation    {@code Σŵᵢ(fᵢ − μ̂)²}, the weighted mean square deviation about it
     * @param sumSqNormalisedWeights {@code Σŵᵢ²} over weights summing to 1 (the Herfindahl index)
     */
    private static double agreement(double mean, double meanSqDeviation, double sumSqNormalisedWeights) {
        if (!(Math.abs(mean) > 0)) {
            return 0.0;
        }
        double residualDegreesOfFreedom = 1.0 - sumSqNormalisedWeights;
        if (!(residualDegreesOfFreedom > 0)) {
            return 0.0;
        }
        double variance = meanSqDeviation / residualDegreesOfFreedom;
        if (!(variance > 0)) {
            return 1.0;
        }
        return Math.max(0.0, Math.min(1.0, Math.abs(mean) / Math.sqrt(mean * mean + variance)));
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
