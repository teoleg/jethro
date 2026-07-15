package io.jethro.trading.riskpnl;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** EWMA covariance + parametric VaR: exact worked numbers where the math is closed-form. */
class CovMathTest {

    /** N days of a CONSTANT return vector: Σ converges exactly to the outer product r·rᵀ. */
    private static List<VarMath.DayVector> constantDays(int n, double a, double b) {
        List<VarMath.DayVector> days = new ArrayList<>();
        LocalDate day = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < n; i++) {
            days.add(new VarMath.DayVector(day.plusDays(i), Map.of("A", a, "B", b)));
        }
        return days;
    }

    @Test
    void constantReturnsConvergeToTheOuterProductAndVarIsExact() {
        // r = (2%, 1%) every day → Σ = [[4e-4, 2e-4],[2e-4, 1e-4]] exactly (EWMA of a constant
        // is the constant). Exposures $10k/$0: σ_p = 10,000 × 0.02 = $200.
        var cov = CovMath.ewmaCovariance(constantDays(30, 0.02, 0.01), 20).orElseThrow();
        int a = cov.instruments().indexOf("A");
        int b = cov.instruments().indexOf("B");
        assertEquals(4e-4, cov.sigma()[a][a], 1e-12);
        assertEquals(2e-4, cov.sigma()[a][b], 1e-12);

        var pv = CovMath.parametricVar(Map.of("A", new BigDecimal("10000")), cov);
        assertEquals(0, new BigDecimal("329.00").compareTo(pv.var95()), "200 × 1.645");
        assertEquals(0, new BigDecimal("412.54").compareTo(pv.es95()), "200 × 2.0627");
        assertEquals(0, new BigDecimal("465.20").compareTo(pv.var99()), "200 × 2.326");
    }

    @Test
    void perfectlyCorrelatedNamesShowRhoOneAndUncoveredExposureIsSkipped() {
        var cov = CovMath.ewmaCovariance(constantDays(30, 0.02, 0.01), 20).orElseThrow();
        // B moves as 0.5×A every single day → ρ with an A-only portfolio is exactly 1.
        double rho = cov.correlationToPortfolio("B", Map.of("A", new BigDecimal("10000"))).orElseThrow();
        assertEquals(1.0, rho, 1e-9);

        var pv = CovMath.parametricVar(
                Map.of("A", new BigDecimal("10000"), "ZZZ", new BigDecimal("5000")), cov);
        assertEquals(0, new BigDecimal("5000.00").compareTo(pv.skippedExposure()),
                "uncovered exposure disclosed, never silently in the number");
        assertEquals(0, new BigDecimal("329.00").compareTo(pv.var95()), "VaR over covered only");
    }

    @Test
    void warmUpAndEmptyIntersectionAreHonest() {
        assertTrue(CovMath.ewmaCovariance(constantDays(5, 0.02, 0.01), 20).isEmpty(), "below min obs");
        List<VarMath.DayVector> disjoint = List.of(
                new VarMath.DayVector(LocalDate.of(2026, 1, 1), Map.of("A", 0.01)),
                new VarMath.DayVector(LocalDate.of(2026, 1, 2), Map.of("B", 0.01)));
        assertTrue(CovMath.ewmaCovariance(disjoint, 2).isEmpty(), "no instrument covers every day");
    }
}
