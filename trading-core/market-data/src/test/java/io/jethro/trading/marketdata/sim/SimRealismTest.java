package io.jethro.trading.marketdata.sim;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ground-truth realism checks for the correlated factor sim (ADR-0026): the cross-asset
 * relationships the hedger (ADR-0038) will neutralize must actually exist, and a regime change
 * must visibly change the <em>shape of traffic</em> — volume, not just price variance. These are
 * the numbers to read: two names sharing the equity factor move together, and a stress regime
 * both raises volatility AND surges volume.
 */
class SimRealismTest {

    // Two equities with different equity betas (a hedgeable pair) + one negatively-USD name.
    private static final List<String> IDS = List.of("HI_BETA", "LO_BETA");
    private static final long[] STARTS = {100_000_000L, 100_000_000L};

    private static FactorModelConfig cfg() {
        // Factor order: [EQUITY, RATES_LEVEL, RATES_SLOPE, USD].
        double[][] corr = {
                {1.00, 0.00, 0.00, -0.20},
                {0.00, 1.00, 0.00, 0.00},
                {0.00, 0.00, 1.00, 0.00},
                {-0.20, 0.00, 0.00, 1.00}};
        return new FactorModelConfig(0.18, 0.08, 5.0, 2.0, 6,
                List.of(
                        new FactorModelConfig.InstrumentSpec("HI_BETA", 0.28, 1.40, 0.0),
                        new FactorModelConfig.InstrumentSpec("LO_BETA", 0.20, 0.70, 0.0)),
                List.of(
                        new FactorModelConfig.RegimeSpec("CALM", 0.05, 0, 0, 1.0, corr),
                        new FactorModelConfig.RegimeSpec("RISK_OFF", -1.20, -4.0, 0, 3.0, corr)),
                new double[][]{{1.0, 0.0}, {0.0, 1.0}}); // no auto-switching; forced per test
    }

    private static CorrelatedFactorSimulator sim(SimControl control) {
        // 300s/day compression so a few thousand ticks is a meaningful sample of market time.
        return new CorrelatedFactorSimulator(2026, cfg(), IDS, STARTS, 0.1, 300, control);
    }

    @Test
    void twoNamesSharingTheEquityFactorArePositivelyCorrelated() {
        SimControl control = new SimControl(1, IDS);
        control.overrideRegime(MarketRegime.CALM);
        var s = sim(control);
        double[] prev = {logPx(s, 0), logPx(s, 1)};
        int n = 8_000;
        double sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0;
        for (int t = 0; t < n; t++) {
            s.nextTick();
            double rx = logPx(s, 0) - prev[0];
            double ry = logPx(s, 1) - prev[1];
            prev[0] = logPx(s, 0);
            prev[1] = logPx(s, 1);
            sx += rx; sy += ry; sxx += rx * rx; syy += ry * ry; sxy += rx * ry;
        }
        double cov = sxy / n - (sx / n) * (sy / n);
        double vx = sxx / n - (sx / n) * (sx / n);
        double vy = syy / n - (sy / n) * (sy / n);
        double rho = cov / Math.sqrt(vx * vy);
        // Shared equity factor (β 1.4 and 0.7) → strongly positive; idio keeps it below 1.
        assertTrue(rho > 0.45, "two equity-factor names must move together, ρ=" + rho);
        assertTrue(rho < 0.99, "idiosyncratic risk must keep them from being identical, ρ=" + rho);
    }

    @Test
    void stressRegimeSurgesBothVolatilityAndVolume() {
        double[] calm = volAndVolume(MarketRegime.CALM);
        double[] risk = volAndVolume(MarketRegime.RISK_OFF);
        double volRatio = risk[0] / calm[0];
        double volumeRatio = risk[1] / calm[1];
        // RISK_OFF is a 3× vol multiple: realized vol clearly higher, and — the point of this
        // test — VOLUME clearly higher too (regime + clustering), so the tape's character changes.
        assertTrue(volRatio > 1.8, "stress must raise realized vol, ratio=" + volRatio);
        assertTrue(volumeRatio > 1.8, "stress must SURGE volume (shape of traffic), ratio=" + volumeRatio);
    }

    /** @return {realized per-tick vol of HI_BETA, mean traded volume of HI_BETA} in a held regime. */
    private double[] volAndVolume(MarketRegime regime) {
        SimControl control = new SimControl(1, IDS);
        control.overrideRegime(regime);
        var s = sim(control);
        double prev = logPx(s, 0);
        int n = 6_000;
        double sxx = 0, sx = 0, vol = 0;
        for (int t = 0; t < n; t++) {
            s.nextTick();
            double r = logPx(s, 0) - prev;
            prev = logPx(s, 0);
            sx += r; sxx += r * r;
            vol += s.nextQuantityScaled(0) / 1_000_000.0;
        }
        double realizedVol = Math.sqrt(sxx / n - (sx / n) * (sx / n));
        return new double[]{realizedVol, vol / n};
    }

    private static double logPx(CorrelatedFactorSimulator s, int i) {
        return Math.log(s.priceScaled(i) / 1_000_000.0);
    }
}
