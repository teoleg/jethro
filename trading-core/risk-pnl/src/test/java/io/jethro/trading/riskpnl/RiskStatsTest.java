package io.jethro.trading.riskpnl;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Statistical measures (Commons Math). VaR/vol are estimates in double, so they assert to
 * a tolerance — the exactness rule (invariant 1) governs the money ledger, not statistical
 * estimates. Worked examples are documented at each site (ADR-0020).
 */
class RiskStatsTest {

    private static final double EPS = 1e-6;

    @Test
    void volatilityIsZeroForFlatOrTooShortSeries() {
        assertEquals(0.0, RiskStats.logReturnVolatility(new double[]{100, 100, 100}), EPS);
        assertEquals(0.0, RiskStats.logReturnVolatility(new double[]{100}), EPS);
    }

    @Test
    void volatilityMatchesHandComputedStdDevOfLogReturns() {
        // prices [100, 110, 100] → log returns [ln(1.1), ln(100/110)] = [+0.0953102, −0.0953102]
        // sample std-dev (n−1): mean 0, var = (r² + r²)/1, std = |r|·√2 = 0.0953102·1.414214
        double expected = Math.abs(Math.log(1.1)) * Math.sqrt(2.0); // ≈ 0.134789
        assertEquals(expected, RiskStats.logReturnVolatility(new double[]{100, 110, 100}), 1e-9);
    }

    @Test
    void parametricVarMatchesTheWorkedExample() {
        // 95% (z=1.645), daily σ=1.5%, exposure 19,000 → VaR ≈ 1.645·0.015·19000 = 468.825
        BigDecimal var = RiskStats.parametricVar(0.015, new BigDecimal("19000"), 1.645);
        assertEquals(468.825, var.doubleValue(), 0.01);
        assertTrue(var.signum() > 0);
    }

    @Test
    void varUsesAbsoluteExposureSoShortsAreRisky() {
        BigDecimal shortVar = RiskStats.parametricVar(0.02, new BigDecimal("-5000"), 2.326);
        assertEquals(2.326 * 0.02 * 5000, shortVar.doubleValue(), 0.01); // 232.60
    }

    @Test
    void herfindahlReflectsConcentration() {
        assertEquals(1.0, RiskStats.herfindahl(List.of(new BigDecimal("100"))), EPS);          // single name
        assertEquals(0.5, RiskStats.herfindahl(
                List.of(new BigDecimal("100"), new BigDecimal("100"))), EPS);                   // 2 equal → 0.5
        assertEquals(0.625, RiskStats.herfindahl(
                List.of(new BigDecimal("300"), new BigDecimal("100"))), EPS);                   // 0.75²+0.25²
        assertEquals(0.0, RiskStats.herfindahl(List.of()), EPS);                                // nothing
    }
}
