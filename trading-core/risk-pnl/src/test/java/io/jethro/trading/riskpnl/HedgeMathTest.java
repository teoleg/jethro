package io.jethro.trading.riskpnl;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exact-value hedge ratio math (ADR-0038). Covariances are hand-picked so β, ρ² and the residual
 * σ are computable to the cent, and the sizing is checked against a worked example — finance
 * correctness beats elegance (CLAUDE.md), so the numbers are asserted, not eyeballed.
 */
class HedgeMathTest {

    /** Axis order is sorted (as {@link CovMath#ewmaCovariance} produces): ES=0, STOCK=1.
     *  σ_ES=1%/day (var 1e-4), σ_STOCK=2%/day (var 4e-4), ρ=0.9 → cov 0.9·0.02·0.01 = 1.8e-4. */
    private static CovMath.Covariance cov() {
        double[][] sigma = {
                {1e-4, 1.8e-4},
                {1.8e-4, 4e-4}};
        return new CovMath.Covariance(List.of("ES", "STOCK"), sigma, 60);
    }

    @Test
    void betaHedgeSizesToTheMinimumVarianceRatioWithEffectivenessAndResidual() {
        // Long $1,000,000 STOCK, hedge with ES @ 5,600 × 50 = $280,000/contract.
        var proposal = HedgeMath.betaHedge(cov(), Map.of("STOCK", new BigDecimal("1000000")),
                "ES", new BigDecimal("5600"), new BigDecimal("50"), 0.25).orElseThrow();

        // β = cov/var = 1.8e-4 / 1e-4 = 1.8 → hedge notional = −1.8 × $1,000,000 = −$1,800,000.
        assertEquals(0, new BigDecimal("-1800000.00").compareTo(proposal.hedgeNotionalUsd()),
                "min-variance hedge notional");
        // qty = −1,800,000 / 280,000 = −6.428571 (short ES).
        assertEquals(0, new BigDecimal("-6.428571").compareTo(proposal.signedQuantity()));
        assertTrue(proposal.signedQuantity().signum() < 0, "a hedge for a long book SELLS the proxy");

        // ρ² = 0.9² = 0.81; book σ = |E|·σ_STOCK = 1,000,000 × 0.02 = $20,000/day;
        // residual = 20,000 × √(1−0.81) = 20,000 × 0.4358899 = $8,717.80.
        assertEquals(0.81, proposal.effectiveness(), 1e-9);
        assertEquals(0, new BigDecimal("20000.00").compareTo(proposal.grossSigmaUsd()));
        assertEquals(0, new BigDecimal("8717.80").compareTo(proposal.residualSigmaUsd()));
        assertTrue(proposal.recommended(), "ρ²=0.81 is well above the 0.25 floor");
    }

    @Test
    void aWeakProxyIsNotRecommendedTheAdviceIsToReduce() {
        // Same book, but a proxy correlated only 0.30 → ρ²=0.09, below the 0.25 floor.
        double[][] sigma = {
                {1e-4, 0.3 * 0.01 * 0.02}, // cov = ρ·σ_F·σ_S = 0.3·0.01·0.02 = 6e-5
                {6e-5, 4e-4}};
        var weakCov = new CovMath.Covariance(List.of("WEAK", "STOCK"), sigma, 60);

        var proposal = HedgeMath.betaHedge(weakCov, Map.of("STOCK", new BigDecimal("1000000")),
                "WEAK", new BigDecimal("100"), new BigDecimal("1"), 0.25).orElseThrow();

        assertEquals(0.09, proposal.effectiveness(), 1e-9);
        assertFalse(proposal.recommended(), "below the floor — the honest call is to reduce, not hedge");
        assertTrue(proposal.rationale().contains("reduce the position"), proposal.rationale());
    }

    @Test
    void unknownProxyOrEmptyBookYieldsNoHedge() {
        assertTrue(HedgeMath.betaHedge(cov(), Map.of("STOCK", new BigDecimal("1000000")),
                "NOPE", new BigDecimal("100"), BigDecimal.ONE, 0.25).isEmpty());
        assertTrue(HedgeMath.betaHedge(cov(), Map.of("STOCK", BigDecimal.ZERO),
                "ES", new BigDecimal("5600"), new BigDecimal("50"), 0.25).isEmpty());
    }

    @Test
    void structuralBetaHedgeSizesFromAssignedBetasWithNoCovariance() {
        // Long $380,000 AAPL (β 1.25) + long $125,000 NVDA (β 1.75), hedge with ES @ 5,450 × 50.
        var s = HedgeMath.structuralBetaHedge(
                Map.of("AAPL", new BigDecimal("380000"), "NVDA", new BigDecimal("125000")),
                Map.of("AAPL", new BigDecimal("1.25"), "NVDA", new BigDecimal("1.75")),
                "ES", new BigDecimal("5450"), new BigDecimal("50")).orElseThrow();
        // systematic = 380,000·1.25 + 125,000·1.75 = 475,000 + 218,750 = $693,750.
        assertEquals(0, new BigDecimal("693750.00").compareTo(s.systematicUsd()));
        assertEquals(0, new BigDecimal("-693750.00").compareTo(s.hedgeNotionalUsd()), "hedge shorts the systematic");
        // qty = −693,750 / (5,450 × 50 = 272,500) = −2.545872 ES.
        assertEquals(0, new BigDecimal("-2.545872").compareTo(s.signedQuantity()));
        assertTrue(s.signedQuantity().signum() < 0, "a long book SELLS the proxy");
        // exposure-weighted beta = 693,750 / 505,000 = 1.3738.
        assertEquals(0, new BigDecimal("1.3738").compareTo(s.weightedBeta()));
    }

    @Test
    void structuralBetaHedgeSkipsNamesWithNoAssignedBeta() {
        // Only AAPL has a beta; the unlabeled name doesn't contribute, and no beta at all → empty.
        var s = HedgeMath.structuralBetaHedge(
                Map.of("AAPL", new BigDecimal("380000"), "XYZ", new BigDecimal("500000")),
                Map.of("AAPL", new BigDecimal("1.25")),
                "ES", new BigDecimal("5450"), new BigDecimal("50")).orElseThrow();
        assertEquals(0, new BigDecimal("475000.00").compareTo(s.systematicUsd()), "only AAPL counts");
        assertTrue(HedgeMath.structuralBetaHedge(Map.of("XYZ", new BigDecimal("500000")),
                Map.of(), "ES", new BigDecimal("5450"), new BigDecimal("50")).isEmpty());
    }

    @Test
    void fxHedgeSellsTheNetForeignValueDirectly() {
        // Long €150,000 worth (as USD), hedge in EUR/USD @ 1.085.
        var proposal = HedgeMath.fxHedge("EURUSD", new BigDecimal("150000"),
                new BigDecimal("1.085000")).orElseThrow();
        assertEquals(0, new BigDecimal("-150000.00").compareTo(proposal.hedgeNotionalUsd()));
        // qty = −150,000 / 1.085 = −138,248.847926
        assertEquals(0, new BigDecimal("-138248.847926").compareTo(proposal.signedQuantity()));
        assertEquals(1.0, proposal.effectiveness(), 1e-12, "a direct FX hedge is fully effective");
        assertTrue(proposal.recommended());
    }
}
