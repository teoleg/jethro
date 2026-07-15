package io.jethro.app.risk;

import io.jethro.trading.riskpnl.CovMath;
import io.jethro.trading.riskpnl.VarMath;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the swap-VaR synthetic-leg convention (rates VaR): exposure = seasoned book DV01 in
 * USD per +1bp, "return" series = day-over-day par-rate change in bp, so pnl_d = DV01 ×
 * Δbp_d is in dollars. Exact worked example (finance-math rule) — VarMath itself is
 * unchanged, this locks the UNITS so a refactor cannot silently mix bp with fractions.
 */
class SwapVarLegTest {

    @Test
    void dv01LegYieldsExactDollarQuantiles() {
        // Pay-fixed book: total seasoned DV01 +$880/bp. 25 days of Δbp with two tail days:
        //   d1 −12bp → 880 × −12 = −10,560   (worst)
        //   d2  −8bp → 880 ×  −8 =  −7,040   (2nd worst)
        //   23 filler days of ±2bp → ±1,760.
        // VaR95 = −pnl[⌊0.05×25⌋=1] = 7,040;  VaR99 = −pnl[0] = 10,560;
        // ES95 = −mean(pnl[0..1]) = (10,560 + 7,040)/2 = 8,800.
        Map<String, BigDecimal> exposures = new LinkedHashMap<>();
        exposures.put("dv01:USD_IRS_5Y", new BigDecimal("880"));
        exposures.put("AAPL", new BigDecimal("100000")); // present but flat — isolates the leg

        List<VarMath.DayVector> days = new ArrayList<>();
        double[] deltas = new double[25];
        deltas[0] = -12;
        deltas[1] = -8;
        for (int i = 2; i < 25; i++) {
            deltas[i] = i % 2 == 0 ? 2 : -2;
        }
        for (int i = 0; i < 25; i++) {
            days.add(new VarMath.DayVector(LocalDate.of(2026, 1, 1).plusDays(i),
                    Map.of("dv01:USD_IRS_5Y", deltas[i], "AAPL", 0.0)));
        }

        VarMath.VarResult r = VarMath.historicalVar(exposures, days, 20);
        assertEquals(0, new BigDecimal("7040.00").compareTo(r.var95()), "VaR95 " + r.var95());
        assertEquals(0, new BigDecimal("10560.00").compareTo(r.var99()), "VaR99 " + r.var99());
        assertEquals(0, new BigDecimal("8800.00").compareTo(r.es95()), "ES95 " + r.es95());
        // Coverage disclosure counts the |DV01| of the swap leg, not gross notional (stated).
        assertEquals(0, new BigDecimal("100880.00").compareTo(r.coveredExposure()));
        assertEquals(0, BigDecimal.ZERO.setScale(2).compareTo(r.skippedExposure()));
    }

    @Test
    void dv01LegFlowsThroughTheEwmaCovarianceForParametricVar() {
        // The same synthetic series must be a first-class covariance axis: a lone swap leg
        // with constant ±2bp days has positive vol, so parametric VaR must be positive and
        // scale linearly with the DV01 exposure (variance–covariance is linear in exposure).
        List<VarMath.DayVector> days = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            days.add(new VarMath.DayVector(LocalDate.of(2026, 1, 1).plusDays(i),
                    Map.of("dv01:USD_IRS_5Y", i % 2 == 0 ? 2.0 : -2.0)));
        }
        CovMath.Covariance cov = CovMath.ewmaCovariance(days, 20).orElseThrow();
        var oneLot = CovMath.parametricVar(Map.of("dv01:USD_IRS_5Y", new BigDecimal("880")), cov);
        var twoLots = CovMath.parametricVar(Map.of("dv01:USD_IRS_5Y", new BigDecimal("1760")), cov);
        assertTrue(oneLot.var95().signum() > 0, "swap leg must carry parametric risk");
        assertEquals(0, oneLot.var95().multiply(new BigDecimal("2")).compareTo(twoLots.var95()),
                "parametric VaR is linear in the DV01 exposure");
    }
}
