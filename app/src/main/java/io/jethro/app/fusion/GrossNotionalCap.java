package io.jethro.app.fusion;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Caps the fused target book's planned GROSS NOTIONAL at the gross the desk is actually permitted to
 * hold (ADR-0137) — the notional counterpart of the σ-relative controls above it.
 *
 * <h2>The gap this closes</h2>
 * Every sizing control in the fusion pipeline is σ-relative. {@link VolatilityBudget} (ADR-0083) shares
 * a per-name cash budget out by measured σ; {@link PortfolioRiskNormaliser} (ADR-0079) scales the book
 * by how much of it is one bet; {@link BookVolatilityBrake} (ADR-0104) caps the book's ex-ante σ at the
 * median of its own series. All three decide how risk is <em>shared out</em> and how much σ the book
 * carries. <b>None of them states a notional</b> — and on a calm tape a measured σ is small, so none of
 * them binds. Nothing in the planner ever asked the one question the deterministic guardrail asks
 * before every order: <em>is this a book we are allowed to hold at all?</em>
 *
 * <p>The consequence is not a missing safety net — the guardrail is still there and still refuses the
 * order. It is that the desk plans toward a book it can never reach, and under ADR-0080 partial
 * adjustment the whole trading path is a function of the distance to that target:
 * <ul>
 *   <li>the ADR-0094 aim e-folds toward an unreachable target, so the held book sits at a small,
 *       roughly constant fraction of it forever — the desk never arrives anywhere and so never holds a
 *       position for the horizon its edge was measured over;</li>
 *   <li>the per-cycle step is {@code a × gap}, and an inflated target inflates {@code gap} by the same
 *       factor, so the desk pays that factor in turnover every single cycle for the privilege;</li>
 *   <li>the ADR-0094 band is a fraction of the name's position at a typical forecast, which is also
 *       proportional to the target — so an inflated book simultaneously over-trades the few names it
 *       is chasing and freezes the many whose band it inflated past their gap.</li>
 * </ul>
 *
 * <h2>The multiplier</h2>
 * Over the names whose USD notional can be asserted, with signed quantities {@code qᵢ}, prices
 * {@code pᵢ} and contract multipliers {@code mᵢ}:
 * <pre>
 *   plannedGross = Σᵢ |qᵢ · pᵢ · mᵢ|          the book's planned gross USD notional
 *   GNM          = min(1, capUsd / plannedGross)
 *   qᵢ'          = qᵢ · GNM
 * </pre>
 * and every covered target is multiplied by GNM, exactly as ADR-0079 multiplies by its PDM.
 *
 * <p><b>{@code capUsd} is not a new number.</b> It is wired from {@code jethro.risk.max-gross-exposure}
 * — the gross cap the deterministic pre-trade guardrail already enforces on the book these orders route
 * to. So this control introduces no money figure of its own (invariant 7 / ADR-0016); it states an
 * identity the desk was violating: <em>do not plan a book you are forbidden to hold</em>.
 *
 * <h2>Properties (all exact, all provable)</h2>
 * <ul>
 *   <li><b>Under the cap ⇒ GNM = 1.</b> A book already inside the gross it is allowed to hold is
 *       returned byte-identical. This control is silent until it binds.</li>
 *   <li><b>Never grows the book.</b> The {@code min} with 1 discards a ratio above one, so a small book
 *       is never levered up to the cap. Same one-way safety property as ADR-0079 and ADR-0104.</li>
 *   <li><b>Sign- and shape-preserving.</b> GNM &gt; 0 and uniform across names, so no name flips side
 *       and the cross-sectional shape the σ controls decided is untouched. This is a level control, not
 *       a view and not a re-weighting.</li>
 *   <li><b>Exact at the boundary.</b> The multiplier is computed in exact decimal and rounded DOWN, so
 *       the scaled book's gross is ≤ the cap by construction, never a rounding step above it.</li>
 * </ul>
 *
 * <h2>Coverage</h2>
 * A name with no price or no contract multiplier has no USD notional that can be asserted, so it enters
 * neither the sum nor the scaling — the same fall-back-rather-than-guess rule {@link
 * PortfolioRiskNormaliser} applies to an uncovered covariance. Such a name is already planned flat by
 * {@link FusionPlanner}, so it contributes nothing either way.
 *
 * <p>Pure and allocation-tolerant: this runs on the 30s fusion cadence, never on the tick path.
 */
public final class GrossNotionalCap {

    /** Quantity scale of the fusion order path — the same one {@link TargetPlanner} sizes at. */
    private static final int QTY_SCALE = 6;

    /** Working scale of the multiplier. Rounded DOWN, so the scaled book can only land under the cap. */
    private static final int RATIO_SCALE = 12;

    /** The capped book plus the disclosure of what was measured to cap it. */
    public record Result(List<FusionPlanner.Target> targets, double multiplier,
                         BigDecimal plannedGrossUsd, int coveredNames) {
    }

    /** The gross the routing book is permitted to hold — {@code jethro.risk.max-gross-exposure}. */
    private final BigDecimal capUsd;

    /**
     * @param capUsd the gross cap the deterministic guardrail enforces on the book these orders route
     *               to. Null or non-positive is rejected: a cap that cannot bind is a disabled control,
     *               and the wiring expresses that by not constructing one at all.
     */
    public GrossNotionalCap(BigDecimal capUsd) {
        if (capUsd == null || capUsd.signum() <= 0) {
            throw new IllegalArgumentException("gross cap must be positive: " + capUsd);
        }
        this.capUsd = capUsd;
    }

    /**
     * The target book with every priced name scaled so the book's planned gross notional is at most the
     * cap, and each order delta recomputed against the scaled target.
     *
     * @param targets       the book as the σ controls left it
     * @param multiplierFor instrument → contract multiplier (ADR-0078): a target quantity is only a USD
     *                      notional after {@code × price × multiplier}
     * @param params        the planning dials — the no-trade band and partial-adjustment rate the
     *                      recomputed delta must respect
     */
    public Result apply(List<FusionPlanner.Target> targets,
                        Function<String, BigDecimal> multiplierFor,
                        FusionPlanner.Params params) {
        List<FusionPlanner.Target> book = targets == null ? List.of() : targets;
        if (book.isEmpty()) {
            return new Result(book, 1.0, BigDecimal.ZERO, 0);
        }
        java.util.Set<String> covered = new java.util.HashSet<>();
        BigDecimal plannedGross = BigDecimal.ZERO;
        for (FusionPlanner.Target t : book) {
            BigDecimal notional = notionalOf(t, multiplierFor);
            if (notional == null) {
                continue; // no price or no contract spec ⇒ no USD notional can be asserted
            }
            covered.add(t.instrument());
            plannedGross = plannedGross.add(notional.abs());
        }
        if (covered.isEmpty() || plannedGross.compareTo(capUsd) <= 0) {
            return new Result(book, 1.0, plannedGross, covered.size()); // already inside the cap — silent
        }
        // Exact decimal, rounded DOWN: the scaled gross is ≤ cap by construction (finance-math rule —
        // every division states its scale and rounding).
        BigDecimal gnm = capUsd.divide(plannedGross, RATIO_SCALE, RoundingMode.DOWN);
        List<FusionPlanner.Target> out = new ArrayList<>(book.size());
        for (FusionPlanner.Target t : book) {
            if (!covered.contains(t.instrument())) {
                out.add(t);
                continue;
            }
            BigDecimal target = t.targetQty().multiply(gnm).setScale(QTY_SCALE, RoundingMode.HALF_EVEN);
            BigDecimal delta = TargetPlanner.orderDelta(target, t.currentQty(),
                    params.bufferFraction(), params.adjustmentRate());
            out.add(new FusionPlanner.Target(t.instrument(), t.combinedForecast(), t.sources(),
                    t.diversificationMultiplier(), t.agreement(), t.price(), target, t.currentQty(), delta,
                    t.contributions()));
        }
        return new Result(out, gnm.doubleValue(), plannedGross, covered.size());
    }

    /** Signed USD notional {@code qty × price × multiplier}, or null when it cannot be asserted. */
    private static BigDecimal notionalOf(FusionPlanner.Target t, Function<String, BigDecimal> multiplierFor) {
        if (t.targetQty() == null || t.targetQty().signum() == 0
                || t.price() == null || t.price().signum() <= 0) {
            return null;
        }
        BigDecimal mult = multiplierFor == null ? null : multiplierFor.apply(t.instrument());
        if (mult == null || mult.signum() <= 0) {
            return null;
        }
        return t.targetQty().multiply(t.price()).multiply(mult);
    }
}
