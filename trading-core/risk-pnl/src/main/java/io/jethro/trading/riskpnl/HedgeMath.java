package io.jethro.trading.riskpnl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;

/**
 * Minimum-variance proxy hedge ratios (ADR-0038). Given a book's exposures and a liquid proxy,
 * how much of the proxy neutralizes the most variance — the standard Ederington answer
 * {@code h* = Cov(ΔS, ΔF) / Var(ΔF)} — with the hedge's <em>measured effectiveness</em> so a
 * weak proxy is never dressed up as a real hedge.
 *
 * <p>Two shapes here:
 * <ul>
 *   <li><b>Equity beta-hedge</b> onto an index future, off the EWMA return covariance
 *       ({@link CovMath.Covariance}): hedge the book's whole equity P&L, not one name.</li>
 *   <li><b>FX hedge</b> — structural, a direct 1:1 sell of the book's net foreign value in the
 *       pair (ρ² ≡ 1, the pair IS the exposure).</li>
 * </ul>
 * Rates DV01-neutral hedging is structural too and lands with the bucket-DV01 wiring (ADR-0038
 * follow-up). Statistics in {@code double}; money crosses to {@link BigDecimal} at the boundary
 * (invariant 1). Pure functions — no state, no I/O — so every ratio is unit-testable to the cent.
 */
public final class HedgeMath {

    private HedgeMath() {
    }

    /**
     * A sized hedge with its evidence.
     *
     * @param proxyInstrumentId   the instrument to trade (e.g. ES)
     * @param signedQuantity      proxy quantity, signed: negative = SELL the proxy (short),
     *                            positive = BUY. Magnitude is contracts/units.
     * @param hedgeNotionalUsd    signed USD notional of the hedge leg (sign matches quantity)
     * @param effectiveness       ρ² — the fraction of the book's variance this hedge removes
     *                            (0..1). 1.0 for a structural (FX/DV01) hedge.
     * @param grossSigmaUsd       the book's daily σ (USD) on this axis BEFORE hedging
     * @param residualSigmaUsd    the daily σ (USD) left AFTER the hedge = grossσ·√(1−ρ²)
     * @param recommended         false when effectiveness is below the floor — the honest call is
     *                            then to reduce the position, not to pretend a weak proxy hedges it
     * @param rationale           one line of the worked math for the UI/audit
     */
    public record HedgeProposal(String proxyInstrumentId, BigDecimal signedQuantity,
                                BigDecimal hedgeNotionalUsd, double effectiveness,
                                BigDecimal grossSigmaUsd, BigDecimal residualSigmaUsd,
                                boolean recommended, String rationale) {
    }

    /**
     * Minimum-variance beta-hedge of a book's equity exposures with one index future.
     *
     * <p>Let the book's equity P&amp;L be {@code Σ Eᵢ·rᵢ} (Eᵢ = USD exposure of name i). Against
     * proxy F with return r_F, total variance is minimized by proxy notional
     * {@code E_F* = −Cov(P&L, r_F)/Var(r_F) = −(Σ Eᵢ·Σ[i,F]) / Σ[F,F]} — opposite sign to the
     * book, as a hedge must be. Effectiveness is {@code ρ² = Cov(P&L,r_F)² / (Var(P&L)·Var(r_F))}.
     *
     * <p>Worked (ADR-0038): long $456,000 systematic equity, ρ=0.92, σ_book=$9,120/day,
     * ES @ 5,600×50 = $280,000/contract → short ≈1.63 ES; residual σ = 9,120·√(1−0.85) ≈ $3,533.
     *
     * @return empty only when the proxy isn't in the covariance, the book has no covered equity
     *         exposure, or a variance is degenerate (0) — never a guessed hedge.
     */
    public static Optional<HedgeProposal> betaHedge(CovMath.Covariance cov,
                                                    Map<String, BigDecimal> equityExposuresUsd,
                                                    String proxyId, BigDecimal proxyPrice,
                                                    BigDecimal proxyMultiplier,
                                                    double effectivenessFloor) {
        int f = cov.instruments().indexOf(proxyId);
        if (f < 0 || proxyPrice == null || proxyPrice.signum() <= 0
                || proxyMultiplier == null || proxyMultiplier.signum() <= 0) {
            return Optional.empty();
        }
        double[][] s = cov.sigma();
        double varF = s[f][f];
        if (varF <= 0) {
            return Optional.empty();
        }
        // Cov(P&L, r_F) = Σ Eᵢ·Σ[i,F]; Var(P&L) = Σᵢ Σⱼ Eᵢ Eⱼ Σ[i,j]. Exposures off the
        // covariance axis simply don't contribute (strict coverage, same as VaR).
        double covPF = 0;
        double varP = 0;
        boolean any = false;
        for (var ei : equityExposuresUsd.entrySet()) {
            int i = cov.instruments().indexOf(ei.getKey());
            if (i < 0) {
                continue;
            }
            double eiUsd = ei.getValue().doubleValue();
            if (eiUsd != 0) {
                any = true;
            }
            covPF += eiUsd * s[i][f];
            for (var ej : equityExposuresUsd.entrySet()) {
                int j = cov.instruments().indexOf(ej.getKey());
                if (j >= 0) {
                    varP += eiUsd * ej.getValue().doubleValue() * s[i][j];
                }
            }
        }
        if (!any || varP <= 0) {
            return Optional.empty();
        }
        double hedgeNotionalUsd = -covPF / varF;                 // E_F* (opposite sign to the book)
        double rho2 = (covPF * covPF) / (varP * varF);           // effectiveness ρ²
        rho2 = Math.max(0.0, Math.min(1.0, rho2));
        double grossSigma = Math.sqrt(varP);
        double residualSigma = grossSigma * Math.sqrt(Math.max(0.0, 1.0 - rho2));
        double qty = hedgeNotionalUsd / (proxyPrice.doubleValue() * proxyMultiplier.doubleValue());
        boolean recommended = rho2 >= effectivenessFloor;

        String rationale = String.format(
                "β-hedge %s: Cov(book,%s)/Var(%s) → %s USD notional (%s %s), ρ²=%.2f, "
                        + "σ %s→%s/day%s",
                proxyId, proxyId, proxyId, money(hedgeNotionalUsd), qty < 0 ? "SELL" : "BUY",
                proxyId, rho2, money(grossSigma), money(residualSigma),
                recommended ? "" : " — below floor " + effectivenessFloor + ", reduce the position instead");

        return Optional.of(new HedgeProposal(proxyId, qty(qty), money(hedgeNotionalUsd),
                rho2, money(grossSigma), money(residualSigma), recommended, rationale));
    }

    /**
     * A structural beta-hedge sized from ASSIGNED fundamental betas — no return history (ADR-0040).
     *
     * @param proxyInstrumentId the index future hedged with (e.g. ES)
     * @param signedQuantity    proxy quantity, signed: negative = SELL (short), positive = BUY
     * @param hedgeNotionalUsd  signed USD notional of the hedge leg
     * @param systematicUsd     the book's assigned-beta systematic exposure Σ βᵢ·Eᵢ (the thing hedged)
     * @param weightedBeta      exposure-weighted average assigned beta, for display; null if net ≈ 0
     * @param rationale         one line of the worked math for the UI/audit
     */
    public record StructuralHedge(String proxyInstrumentId, BigDecimal signedQuantity,
                                  BigDecimal hedgeNotionalUsd, BigDecimal systematicUsd,
                                  BigDecimal weightedBeta, String rationale) {
    }

    /**
     * Structural (fundamental) equity beta-hedge (ADR-0040). Systematic exposure is the sum of each
     * name's assigned beta times its USD exposure, {@code Σ βᵢ·Eᵢ}; the hedge shorts that notional in
     * the proxy: {@code qty = −Σ βᵢ·Eᵢ / (price × multiplier)}. No covariance — so effectiveness is
     * <em>asserted</em> by the assigned betas, never a measured ρ² (that distinction is the caller's
     * to surface). This is the history-free floor under {@link #betaHedge}; names with no assigned
     * beta simply don't contribute (they aren't structurally hedgeable yet).
     *
     * <p>Worked: long $380,000 AAPL (β 1.25) + long $125,000 NVDA (β 1.75) → systematic
     * 380,000·1.25 + 125,000·1.75 = $693,750; ES @ 5,450 × 50 = $272,500/contract →
     * short 693,750/272,500 = <b>2.545872 ES</b>.
     *
     * @return empty when the proxy price/multiplier is missing or non-positive, or no name carries
     *         both an assigned beta and a non-zero exposure (nothing to hedge).
     */
    public static Optional<StructuralHedge> structuralBetaHedge(Map<String, BigDecimal> equityExposuresUsd,
                                                                Map<String, BigDecimal> assignedBetas,
                                                                String proxyId, BigDecimal proxyPrice,
                                                                BigDecimal proxyMultiplier) {
        if (proxyId == null || proxyPrice == null || proxyPrice.signum() <= 0
                || proxyMultiplier == null || proxyMultiplier.signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal systematic = BigDecimal.ZERO;
        BigDecimal netExposure = BigDecimal.ZERO;
        boolean any = false;
        for (var e : equityExposuresUsd.entrySet()) {
            BigDecimal beta = assignedBetas.get(e.getKey());
            BigDecimal eUsd = e.getValue();
            if (beta == null || eUsd == null || eUsd.signum() == 0) {
                continue; // no assigned beta or no exposure → not part of the structural hedge
            }
            systematic = systematic.add(beta.multiply(eUsd));
            netExposure = netExposure.add(eUsd);
            any = true;
        }
        if (!any || systematic.signum() == 0) {
            return Optional.empty();
        }
        BigDecimal hedgeNotional = systematic.negate();
        BigDecimal qty = hedgeNotional.divide(proxyPrice.multiply(proxyMultiplier), 6, RoundingMode.HALF_EVEN);
        BigDecimal weightedBeta = netExposure.signum() == 0 ? null
                : systematic.divide(netExposure, 4, RoundingMode.HALF_EVEN);
        String side = qty.signum() < 0 ? "SELL" : "BUY";
        String rationale = String.format(
                "structural β-hedge %s: Σβ·E = %s systematic → %s %s %s (assigned betas, no covariance)",
                proxyId, systematic.setScale(2, RoundingMode.HALF_UP).toPlainString(), side,
                qty.abs().toPlainString(), proxyId);
        return Optional.of(new StructuralHedge(proxyId, qty, hedgeNotional.setScale(2, RoundingMode.HALF_UP),
                systematic.setScale(2, RoundingMode.HALF_UP), weightedBeta, rationale));
    }

    /**
     * Structural FX hedge: sell the book's net foreign value in the pair (ρ² ≡ 1 — the pair is
     * the exposure). {@code netForeignValueUsd} is the book's non-USD value expressed in USD
     * (positive = long the foreign currency); the hedge shorts that many USD of the XXXUSD pair.
     * Quantity is in units of the base currency = |netForeignValueUsd| / pairPrice (USD-per-unit).
     *
     * @return empty when the exposure or price is non-positive/absent (nothing to hedge).
     */
    public static Optional<HedgeProposal> fxHedge(String pairId, BigDecimal netForeignValueUsd,
                                                  BigDecimal pairPrice) {
        if (pairId == null || netForeignValueUsd == null || netForeignValueUsd.signum() == 0
                || pairPrice == null || pairPrice.signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal hedgeNotionalUsd = netForeignValueUsd.negate(); // opposite sign
        BigDecimal quantity = hedgeNotionalUsd.divide(pairPrice, 6, RoundingMode.HALF_EVEN);
        String side = quantity.signum() < 0 ? "SELL" : "BUY";
        String rationale = String.format("FX hedge %s: net foreign %s USD → %s %s %s (direct, ρ²=1.00)",
                pairId, money(netForeignValueUsd.doubleValue()), side, quantity.abs().toPlainString(), pairId);
        return Optional.of(new HedgeProposal(pairId, quantity, hedgeNotionalUsd.setScale(2, RoundingMode.HALF_UP),
                1.0, BigDecimal.ZERO, BigDecimal.ZERO, true, rationale));
    }

    private static BigDecimal money(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal qty(double v) {
        return BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_EVEN);
    }
}
