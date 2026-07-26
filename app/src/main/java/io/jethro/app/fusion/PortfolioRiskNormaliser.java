package io.jethro.app.fusion;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.function.Function;

/**
 * Scales the whole fused target book by the amount the names in it are ONE bet rather than many
 * (ADR-0079) — the instrument-level counterpart of {@link ForecastCombiner}'s source-level
 * diversification multiplier.
 *
 * <h2>The gap this closes</h2>
 * {@code unit-notional-usd} is the desk's cash-at-risk <em>per name</em> at a typical forecast. Nothing
 * downstream of it says anything about the <em>book</em>: {@link TargetPlanner} sizes each name in
 * isolation, so a cross-section of N names is N independent applications of a per-name budget. That is
 * the right answer only if the names are mutually uncorrelated. When they are not — and a book that is
 * short every name in the universe at once is the extreme case — the book's risk grows toward N× the
 * per-name budget instead of √N×, and the desk carries an exposure nobody chose.
 *
 * <h2>The multiplier</h2>
 * Over the names covered by the measured covariance Σ, with signed USD notionals {@code eᵢ}:
 * <pre>
 *   σ_actual = √( Σᵢ Σⱼ eᵢ eⱼ Σᵢⱼ )      the book's daily σ as it actually correlates
 *   σ_indep  = √( Σᵢ eᵢ² Σᵢᵢ )           the same book if its names were mutually independent
 *   PDM      = min(1, σ_indep / σ_actual)
 * </pre>
 * and every covered target is multiplied by PDM. σ_indep is not an aspiration — it is precisely the
 * risk the per-name budget already implies, because sizing each name alone IS the independence
 * assumption. So this does not re-set anyone's dial or introduce a book-level money number: it makes
 * the book honour the risk statement the per-name dial was already making.
 *
 * <h2>Properties (all exact, all provable)</h2>
 * <ul>
 *   <li><b>Uncorrelated ⇒ PDM = 1.</b> The off-diagonals vanish, the two sums coincide, and a genuinely
 *       diversified book is left byte-identical. Diversification is never penalised.</li>
 *   <li><b>N equal, perfectly correlated names ⇒ PDM = 1/√N.</b> σ_indep = eσ√N, σ_actual = eσN. The
 *       book then carries exactly one per-name budget of risk, which is what "one bet" deserves.</li>
 *   <li><b>Never grows the book.</b> Negative correlations make σ_actual &lt; σ_indep and the ratio
 *       exceed 1; the cap discards that. A book that looks internally hedged does not get levered up on
 *       the strength of an estimated correlation — the same one-way safety property ADR-0076 gave the
 *       source-level multiplier.</li>
 *   <li><b>Sign- and view-preserving.</b> PDM &gt; 0 and uniform, so no name flips side and the
 *       cross-sectional shape of the forecast is untouched. This is a size control, not a view.</li>
 * </ul>
 *
 * <h2>Coverage</h2>
 * Only names the estimate actually covers enter the sums, and only those names are scaled. An uncovered
 * name is left exactly as planned: with no measurement there is no claim to make about it, and the
 * house rule is to fall back rather than guess a ρ ({@code PortfolioCorrelationSource}, ADR-0016 /
 * invariant 7). So the control shrinks precisely the concentration it can measure, and no more.
 *
 * <p>Pure and allocation-tolerant: this runs on the 30s fusion cadence, never on the tick path.
 */
public final class PortfolioRiskNormaliser {

    /** Quantity scale of the fusion order path — the same one {@link TargetPlanner} sizes at. */
    private static final int QTY_SCALE = 6;

    /** The scaled book plus the disclosure of what was measured to scale it. */
    public record Scaled(List<FusionPlanner.Target> targets, double multiplier, int coveredNames) {
    }

    private PortfolioRiskNormaliser() {
    }

    /**
     * The target book with every covered name scaled by the portfolio diversification multiplier, and
     * its order delta recomputed against the scaled target.
     *
     * @param targets       the planned book from {@link FusionPlanner#plan}
     * @param multiplierFor instrument → contract multiplier (ADR-0078): a target quantity is only a USD
     *                      notional after {@code × price × multiplier}. A name whose spec is unknown is
     *                      already planned flat, so it contributes nothing either way.
     * @param covariance    the measured daily-return covariance; {@link ReturnCovarianceSource#NONE}
     *                      leaves the book untouched
     * @param params        the planning dials — the no-trade band and partial-adjustment rate the
     *                      recomputed delta must respect
     */
    public static Scaled apply(List<FusionPlanner.Target> targets,
                               Function<String, BigDecimal> multiplierFor,
                               ReturnCovarianceSource covariance,
                               FusionPlanner.Params params) {
        if (targets == null || targets.isEmpty() || covariance == null) {
            return new Scaled(targets == null ? List.of() : targets, 1.0, 0);
        }
        List<String> covered = new ArrayList<>();
        List<Double> notionals = new ArrayList<>();
        for (FusionPlanner.Target t : targets) {
            if (t.targetQty() == null || t.targetQty().signum() == 0 || t.price() == null) {
                continue; // a flat target carries no risk to normalise
            }
            if (covariance.covariance(t.instrument(), t.instrument()).isEmpty()) {
                continue; // uncovered — no measurement, no claim (ADR-0016 / invariant 7)
            }
            BigDecimal mult = multiplierFor == null ? null : multiplierFor.apply(t.instrument());
            if (mult == null || mult.signum() <= 0) {
                continue; // no contract spec ⇒ no USD notional can be asserted
            }
            covered.add(t.instrument());
            // Signed USD notional: quantity × price × contract multiplier — the same arithmetic
            // PositionRisk values the resulting position with (ADR-0078). Money is exact up to here;
            // it enters the statistic as a double exactly where CovMath draws that boundary.
            notionals.add(t.targetQty().multiply(t.price()).multiply(mult).doubleValue());
        }
        double pdm = multiplier(covered, notionals, covariance);
        if (pdm >= 1.0 || covered.isEmpty()) {
            return new Scaled(targets, 1.0, covered.size());
        }
        BigDecimal scale = BigDecimal.valueOf(pdm);
        List<FusionPlanner.Target> out = new ArrayList<>(targets.size());
        for (FusionPlanner.Target t : targets) {
            if (!covered.contains(t.instrument())) {
                out.add(t);
                continue;
            }
            BigDecimal target = t.targetQty().multiply(scale).setScale(QTY_SCALE, RoundingMode.HALF_EVEN);
            BigDecimal delta = TargetPlanner.orderDelta(target, t.currentQty(),
                    params.bufferFraction(), params.adjustmentRate());
            out.add(new FusionPlanner.Target(t.instrument(), t.combinedForecast(), t.sources(),
                    t.diversificationMultiplier(), t.price(), target, t.currentQty(), delta,
                    t.contributions()));
        }
        return new Scaled(out, pdm, covered.size());
    }

    /**
     * {@code min(1, σ_indep / σ_actual)} over the covered book; 1.0 when there is nothing to say —
     * fewer than two names (one name cannot be concentrated against itself), a degenerate estimate, or
     * a book whose measured σ is zero.
     */
    static double multiplier(List<String> instruments, List<Double> notionals, ReturnCovarianceSource cov) {
        int n = instruments.size();
        if (n < 2) {
            return 1.0;
        }
        double independentVar = 0;
        double actualVar = 0;
        for (int i = 0; i < n; i++) {
            double ei = notionals.get(i);
            for (int j = 0; j < n; j++) {
                OptionalDouble sij = cov.covariance(instruments.get(i), instruments.get(j));
                if (sij.isEmpty()) {
                    return 1.0; // an incomplete matrix cannot be aggregated — make no claim
                }
                double term = ei * notionals.get(j) * sij.getAsDouble();
                actualVar += term;
                if (i == j) {
                    independentVar += term;
                }
            }
        }
        if (!(actualVar > 0) || !(independentVar > 0)) {
            return 1.0;
        }
        double ratio = Math.sqrt(independentVar) / Math.sqrt(actualVar);
        return Math.min(1.0, ratio);
    }
}
