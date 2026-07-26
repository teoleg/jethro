package io.jethro.app.fusion;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.Function;

/**
 * Splits the desk's per-name cash budget by each name's own MEASURED volatility (ADR-0083), so every
 * name in the fused book contributes the same amount of standalone risk rather than the same amount of
 * cash. The volatility half of the concentration problem whose correlation half
 * {@link PortfolioRiskNormaliser} already prices.
 *
 * <h2>The gap this closes</h2>
 * {@code unit-notional-usd} is ONE flat cash figure applied to every instrument, so a name's risk
 * contribution is proportional to its own volatility. {@link PortfolioRiskNormaliser} makes this
 * explicit in its own derivation — the independence benchmark it scales back to is
 * {@code σ_indep = √(Σᵢ eᵢ² Σᵢᵢ)}, whose terms are unequal across {@code i} precisely because
 * {@code Σᵢᵢ} is. It corrects for the names being one bet; nothing corrects for one name being seven
 * times the bet of another. On a multi-asset universe that dispersion is not a detail: a broad equity
 * name and a major FX cross differ in daily σ by close to an order of magnitude, so a "23-name
 * cross-section" sized in flat cash is, in risk terms, a handful of names plus rounding — and the
 * desk's realised PnL per unit of exposure is set by whichever of them happened to move.
 *
 * <h2>The split</h2>
 * Over the names {@code C} that are being sized (non-zero target, valued price and contract spec) AND
 * are covered by the measured daily-return covariance {@code Σ}:
 * <pre>
 *   σᵢ      = √Σᵢᵢ                                  each name's own measured daily return vol
 *   σ̃ᵢ      = winsorise(σᵢ) into [p, 100−p] of {σ}   robust: one degenerate estimate cannot dominate
 *   σ_ref   = |C| / Σᵢ (1 / σ̃ᵢ)                      the HARMONIC mean of the winsorised σ
 *   kᵢ      = σ_ref / σ̃ᵢ                             this name's share of the per-name budget
 * </pre>
 * and each covered target is multiplied by {@code kᵢ} (targets are linear in the budget, so scaling the
 * quantity IS scaling the cash). This is standard risk-parity / vol-scaled sizing — the same
 * {@code 1/σ} weight behind Carver's position formula (<i>Systematic Trading</i>, 2015, ch. 10), Qian's
 * risk-parity construction ("Risk Parity Portfolios", 2005), and the volatility scaling that carries
 * the time-series-momentum results the trend sensor already cites (Moskowitz, Ooi &amp; Pedersen,
 * <i>JFE</i> 2012). It is applied at the sizing step, before the correlation control, so the two
 * compose: this equalises what each name brings, that prices how much of it is the same bet.
 *
 * <h2>Properties (all exact, all provable)</h2>
 * <ul>
 *   <li><b>Budget-neutral by construction.</b> {@code Σᵢ kᵢ = σ_ref · Σᵢ(1/σ̃ᵢ) = |C|} — the covered
 *       names' budgets still sum to exactly {@code |C| × unitNotional}. Nobody's dial is re-set and no
 *       new money number is introduced: the existing dial keeps its exact meaning as the cash for a
 *       name of TYPICAL volatility, and all this does is stop pretending every name is that name.</li>
 *   <li><b>Equal risk contribution.</b> {@code notionalᵢ × σ̃ᵢ = unitNotional × σ_ref} for every covered
 *       name — identical, by construction. That is the whole point: the cross-sectional forecast, not
 *       the accident of which names are volatile, decides where the book's risk sits.</li>
 *   <li><b>Never grows the book</b> (the one-way property ADR-0076 and ADR-0079 both hold). Budget
 *       neutrality is neutrality of the BUDGETS; realised notional also carries each name's forecast,
 *       so a cross-section whose strongest views sit in its quietest names could still plan more gross
 *       than the flat rule did. It is therefore capped: if the redistributed book's gross exceeds the
 *       gross it replaced, every covered name is scaled back uniformly until it does not. The change
 *       can redistribute risk or shrink the book; it can never lever it up on the strength of an
 *       estimated σ.</li>
 *   <li><b>Sign- and view-preserving.</b> {@code kᵢ > 0}, so no name flips side and no forecast is
 *       reinterpreted. This is a budget control, not a view.</li>
 *   <li><b>Degenerate-safe.</b> Fewer than two covered names, a non-positive or non-finite σ, or an
 *       absent covariance all leave the book byte-identical.</li>
 * </ul>
 *
 * <h2>Coverage</h2>
 * Only names the estimate actually covers are re-budgeted, and the reference σ is computed over those
 * same names. A name with no measured σ keeps the flat dial exactly as today: with no measurement there
 * is no claim to make, and inventing a volatility to fill the gap would be a risk number without
 * provenance (ADR-0016 / invariant 7). Every σ here is measured from the same EWMA daily-return
 * covariance that already prices the parametric VaR, the ADR-0038 hedge advisor and the ADR-0079
 * multiplier — so the sizer and the risk engine cannot disagree about how volatile a name is.
 *
 * <p>Pure and allocation-tolerant: this runs on the 30s fusion cadence, never on the tick path. Money
 * stays exact ({@link BigDecimal} quantities, explicit scale and {@link RoundingMode}); σ enters as a
 * {@code double} statistic at exactly the boundary {@link ReturnCovarianceSource} already draws.
 */
public final class VolatilityBudget {

    /** Quantity scale of the fusion order path — the same one {@link TargetPlanner} sizes at. */
    private static final int QTY_SCALE = 6;

    /**
     * The re-budgeted book plus the disclosure of what was measured to produce it.
     *
     * @param coveredNames how many names were re-budgeted (the rest kept the flat dial)
     * @param dispersion   {@code max σ̃ / min σ̃} over those names — how unequal the flat budget was;
     *                     1.0 means the flat budget was already risk-equal and nothing moved
     * @param leverCap     the uniform scale-back applied to hold the book's gross at or below the
     *                     gross it replaced; 1.0 means the cap did not bind
     */
    public record Scaled(List<FusionPlanner.Target> targets, int coveredNames, double dispersion,
                         double leverCap) {
    }

    private VolatilityBudget() {
    }

    /**
     * The target book with every covered name's budget re-split by its own measured volatility, and its
     * order delta recomputed against the re-budgeted target.
     *
     * @param targets       the planned book from {@link FusionPlanner#plan}
     * @param multiplierFor instrument → contract multiplier (ADR-0078): a target quantity is only a USD
     *                      notional after {@code × price × multiplier}
     * @param covariance    the measured daily-return covariance; {@link ReturnCovarianceSource#NONE}
     *                      leaves the book untouched
     * @param winsorPct     the percentile each tail of the measured σ cross-section is clipped at — a
     *                      robust-scaling convention, not a money, risk or exposure number: it moves no
     *                      total, it only bounds how far one extreme σ ESTIMATE may tilt the split.
     *                      Values outside {@code [0, 50)} are treated as 0 (no winsorisation)
     * @param params        the planning dials — the no-trade band and partial-adjustment rate the
     *                      recomputed delta must respect
     */
    public static Scaled apply(List<FusionPlanner.Target> targets,
                               Function<String, BigDecimal> multiplierFor,
                               ReturnCovarianceSource covariance,
                               double winsorPct,
                               FusionPlanner.Params params) {
        if (targets == null || targets.isEmpty() || covariance == null) {
            return new Scaled(targets == null ? List.of() : targets, 0, 1.0, 1.0);
        }
        // The names actually being sized: a flat target has no budget to re-split, and a name with no
        // measured σ of its own makes no claim (ADR-0016 / invariant 7) and keeps the flat dial.
        List<String> covered = new ArrayList<>();
        List<Double> sigmas = new ArrayList<>();
        List<BigDecimal> notionals = new ArrayList<>();
        for (FusionPlanner.Target t : targets) {
            if (t.targetQty() == null || t.targetQty().signum() == 0 || t.price() == null
                    || t.price().signum() <= 0) {
                continue;
            }
            BigDecimal mult = multiplierFor == null ? null : multiplierFor.apply(t.instrument());
            if (mult == null || mult.signum() <= 0) {
                continue; // no contract spec ⇒ no USD notional can be asserted (ADR-0078)
            }
            OptionalDouble var = covariance.covariance(t.instrument(), t.instrument());
            if (var.isEmpty() || !(var.getAsDouble() > 0) || !Double.isFinite(var.getAsDouble())) {
                continue; // uncovered or degenerate — no measurement, no claim
            }
            double sigma = Math.sqrt(var.getAsDouble());
            if (!(sigma > 0) || !Double.isFinite(sigma)) {
                continue;
            }
            covered.add(t.instrument());
            sigmas.add(sigma);
            notionals.add(t.targetQty().multiply(t.price()).multiply(mult).abs());
        }
        if (covered.size() < 2) {
            return new Scaled(targets, covered.size(), 1.0, 1.0); // one name cannot be unequal to itself
        }
        List<Double> winsorised = winsorise(sigmas, winsorPct);
        double sumInverse = 0;
        for (double s : winsorised) {
            sumInverse += 1.0 / s;
        }
        if (!(sumInverse > 0) || !Double.isFinite(sumInverse)) {
            return new Scaled(targets, covered.size(), 1.0, 1.0);
        }
        // The harmonic mean: the reference volatility at which the existing per-name dial is unchanged.
        // Chosen because it is the ONLY reference that makes the covered budgets sum to what they
        // summed to before (Σ kᵢ = σ_ref · Σ 1/σ̃ᵢ = |C|) — the split invents no cash.
        double sigmaRef = winsorised.size() / sumInverse;
        Map<String, Double> factorByInstrument = new LinkedHashMap<>();
        double grossBefore = 0;
        double grossAfter = 0;
        for (int i = 0; i < covered.size(); i++) {
            double k = sigmaRef / winsorised.get(i);
            if (!(k > 0) || !Double.isFinite(k)) {
                return new Scaled(targets, covered.size(), 1.0, 1.0);
            }
            factorByInstrument.put(covered.get(i), k);
            double notional = notionals.get(i).doubleValue();
            grossBefore += notional;
            grossAfter += notional * k;
        }
        // One-way cap: budget neutrality is neutrality of the BUDGETS, and realised gross also carries
        // each name's forecast — so the redistributed book is held to the gross it replaced. This can
        // only ever shrink it; a σ estimate must never be the reason the desk carries more exposure.
        // The tolerance is not a dial: a pure redistribution sums to the same gross in exact arithmetic,
        // so the only thing a 1-ulp excess can represent is rounding in the σ arithmetic. Shaving the
        // book for that would be a silent, meaningless haircut on every cycle.
        double leverCap = 1.0;
        if (grossBefore > 0 && Double.isFinite(grossAfter)
                && grossAfter > grossBefore * (1 + 1e-12)) {
            leverCap = grossBefore / grossAfter;
        }
        double dispersion = winsorised.stream().max(Comparator.naturalOrder()).orElse(1.0)
                / winsorised.stream().min(Comparator.naturalOrder()).orElse(1.0);
        List<FusionPlanner.Target> out = new ArrayList<>(targets.size());
        for (FusionPlanner.Target t : targets) {
            Double k = factorByInstrument.get(t.instrument());
            if (k == null) {
                out.add(t); // uncovered — the flat dial stands, exactly as before this change
                continue;
            }
            BigDecimal target = t.targetQty().multiply(BigDecimal.valueOf(k * leverCap))
                    .setScale(QTY_SCALE, RoundingMode.HALF_EVEN);
            BigDecimal delta = TargetPlanner.orderDelta(target, t.currentQty(),
                    params.bufferFraction(), params.adjustmentRate());
            out.add(new FusionPlanner.Target(t.instrument(), t.combinedForecast(), t.sources(),
                    t.diversificationMultiplier(), t.price(), target, t.currentQty(), delta,
                    t.contributions()));
        }
        return new Scaled(out, covered.size(), dispersion, leverCap);
    }

    /**
     * The σ cross-section with each tail clipped to its {@code pct}-th percentile (nearest-rank), so a
     * single degenerate estimate — a name whose measured σ is near zero because its history is thin or
     * stale — cannot be handed an outsized share of the book. Bounds derived FROM the measurement, not
     * asserted beside it. {@code pct} outside {@code [0, 50)} disables clipping.
     *
     * <p><b>What this does and does not promise, stated plainly.</b> Nearest-rank selects an INTERIOR
     * rank — and so actually clips the lowest name — only once {@code ⌈pct/100 · n⌉ ≥ 2}, i.e. once
     * {@code n > 100/pct} names are covered (at the shipped 10%, more than ten). Below that the lowest
     * σ is its own bound and nothing is clipped, because a percentile of a handful of points is not a
     * statement anyone should act on. What still holds unconditionally is the structural bound: the
     * factors sum to {@code |C|} by construction, so even a wholly degenerate σ cannot take more than
     * the entire covered budget, and the one-way gross cap in {@link #apply} stops the book growing
     * either way. Concentration, not leverage, is the residual failure mode below the threshold.
     */
    static List<Double> winsorise(List<Double> sigmas, double pct) {
        int n = sigmas.size();
        if (n == 0 || !(pct > 0) || pct >= 50 || !Double.isFinite(pct)) {
            return List.copyOf(sigmas);
        }
        List<Double> sorted = new ArrayList<>(sigmas);
        sorted.sort(Comparator.naturalOrder());
        double lo = sorted.get(rank(pct, n));
        double hi = sorted.get(rank(100 - pct, n));
        List<Double> out = new ArrayList<>(n);
        for (double s : sigmas) {
            out.add(Math.min(Math.max(s, lo), hi));
        }
        return out;
    }

    /** Nearest-rank percentile index into a 0-based ascending array of {@code n} values. */
    private static int rank(double pct, int n) {
        int idx = (int) Math.ceil(pct / 100.0 * n) - 1;
        return Math.max(0, Math.min(n - 1, idx));
    }
}
