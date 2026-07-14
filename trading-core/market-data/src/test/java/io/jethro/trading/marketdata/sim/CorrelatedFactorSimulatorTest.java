package io.jethro.trading.marketdata.sim;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Statistical validation of the correlated factor simulator (ADR-0026): determinism, factor
 * loadings, per-regime cross-asset coherence (risk-off vs inflation-shock stock/bond
 * correlation signs), and fat tails. Fixed seeds; sample sizes chosen so the tested effects
 * are many standard errors above noise.
 */
class CorrelatedFactorSimulatorTest {

    private static final double TICK_SECONDS = 0.1;
    private static final double SIM_SECONDS_PER_DAY = 120;

    /** One-regime config isolating the correlation structure (zero drift unless stated). */
    private static FactorModelConfig singleRegime(String name, double eqDrift, double[][] corr) {
        return new FactorModelConfig(0.16, 0.07, 5.0, 2.0, 5,
                List.of(new FactorModelConfig.InstrumentSpec("ES", 0.16, 1.0, 0.0),
                        new FactorModelConfig.InstrumentSpec("AAPL", 0.28, 1.2, 0.0),
                        new FactorModelConfig.InstrumentSpec("EURUSD", 0.08, 0.10, -1.0)),
                List.of(new FactorModelConfig.RegimeSpec(name, eqDrift, 0, 0, 1.0, corr)),
                new double[][]{{1.0}});
    }

    private static final double[][] RISK_OFF_CORR = {
            {1.00, 0.60, 0.10, -0.60},
            {0.60, 1.00, -0.20, -0.40},
            {0.10, -0.20, 1.00, 0.00},
            {-0.60, -0.40, 0.00, 1.00}};

    private static final double[][] INFLATION_CORR = {
            {1.00, -0.60, 0.10, -0.40},
            {-0.60, 1.00, -0.20, 0.50},
            {0.10, -0.20, 1.00, 0.00},
            {-0.40, 0.50, 0.00, 1.00}};

    private static CorrelatedFactorSimulator sim(long seed, FactorModelConfig cfg) {
        return new CorrelatedFactorSimulator(seed, cfg, List.of("ES", "AAPL", "EURUSD"),
                new long[]{5_450_000_000L, 190_000_000L, 1_085_000L}, TICK_SECONDS, SIM_SECONDS_PER_DAY);
    }

    @Test
    void sameSeedSameTape() {
        var a = sim(42, singleRegime("CALM", 0, RISK_OFF_CORR));
        var b = sim(42, singleRegime("CALM", 0, RISK_OFF_CORR));
        for (int t = 0; t < 1_000; t++) {
            a.nextTick();
            b.nextTick();
            for (int i = 0; i < 3; i++) {
                assertEquals(a.priceScaled(i), b.priceScaled(i), "tick " + t + " instrument " + i);
            }
            assertEquals(a.lastLevelDelta(), b.lastLevelDelta(), 0.0);
        }
    }

    @Test
    void singleNamesRideTheEquityFactor() {
        var s = sim(7, singleRegime("CALM", 0, RISK_OFF_CORR));
        double[][] r = returns(s, 20_000);
        assertTrue(corr(r[0], r[1]) > 0.5,
                "AAPL (β=1.2) must co-move with ES (β=1.0): corr=" + corr(r[0], r[1]));
    }

    @Test
    void riskOffCouplesEquitiesYieldsAndUsd() {
        var s = sim(11, singleRegime("RISK_OFF", 0, RISK_OFF_CORR));
        int n = 20_000;
        double[] es = new double[n];
        double[] eur = new double[n];
        double[] dLevel = new double[n];
        double prevEs = s.priceScaled(0);
        double prevEur = s.priceScaled(2);
        for (int t = 0; t < n; t++) {
            s.nextTick();
            es[t] = Math.log(s.priceScaled(0) / prevEs);
            eur[t] = Math.log(s.priceScaled(2) / prevEur);
            dLevel[t] = s.lastLevelDelta();
            prevEs = s.priceScaled(0);
            prevEur = s.priceScaled(2);
        }
        // Flight to quality: stocks and YIELDS fall together (bond futures rise as stocks fall).
        assertTrue(corr(es, dLevel) > 0.3, "risk-off: corr(equity, yield change)=" + corr(es, dLevel));
        // USD bid when stocks fall: EURUSD falls WITH stocks → positive equity/EURUSD correlation.
        assertTrue(corr(es, eur) > 0.2, "risk-off: corr(equity, EURUSD)=" + corr(es, eur));
    }

    @Test
    void inflationShockFlipsTheStockBondCorrelation() {
        var s = sim(13, singleRegime("INFLATION_SHOCK", 0, INFLATION_CORR));
        int n = 20_000;
        double[] es = new double[n];
        double[] dLevel = new double[n];
        double prevEs = s.priceScaled(0);
        for (int t = 0; t < n; t++) {
            s.nextTick();
            es[t] = Math.log(s.priceScaled(0) / prevEs);
            dLevel[t] = s.lastLevelDelta();
            prevEs = s.priceScaled(0);
        }
        // 2022 pattern: stocks down WHILE yields up → negative equity/yield-change correlation.
        assertTrue(corr(es, dLevel) < -0.3, "inflation shock: corr(equity, yield change)=" + corr(es, dLevel));
    }

    @Test
    void studentTInnovationsProduceFatTails() {
        var s = sim(17, singleRegime("CALM", 0, RISK_OFF_CORR));
        double[] es = returns(s, 20_000)[0];
        assertTrue(excessKurtosis(es) > 1.0,
                "t(5) innovations must be leptokurtic: excess kurtosis=" + excessKurtosis(es));
    }

    @Test
    void strongDriftMovesTheMean() {
        // Exaggerated drift (synthetic, detectability >> noise): −50/yr annualized.
        var s = sim(19, singleRegime("TREND_DOWN", -50, RISK_OFF_CORR));
        double[] es = returns(s, 20_000)[0];
        assertTrue(mean(es) < 0, "strongly negative drift must pull the mean return negative");
    }

    @Test
    void nonPsdCorrelationFailsFast() {
        double[][] bad = {
                {1.0, 0.99, 0.99, 0.0},
                {0.99, 1.0, -0.99, 0.0},
                {0.99, -0.99, 1.0, 0.0},
                {0.0, 0.0, 0.0, 1.0}};
        assertThrows(IllegalArgumentException.class,
                () -> sim(1, singleRegime("CALM", 0, bad)));
    }

    // ---- helpers ----

    private static double[][] returns(CorrelatedFactorSimulator s, int n) {
        double[][] out = new double[3][n];
        double[] prev = {s.priceScaled(0), s.priceScaled(1), s.priceScaled(2)};
        for (int t = 0; t < n; t++) {
            s.nextTick();
            for (int i = 0; i < 3; i++) {
                out[i][t] = Math.log(s.priceScaled(i) / prev[i]);
                prev[i] = s.priceScaled(i);
            }
        }
        return out;
    }

    private static double mean(double[] x) {
        double m = 0;
        for (double v : x) {
            m += v;
        }
        return m / x.length;
    }

    private static double corr(double[] x, double[] y) {
        double mx = mean(x);
        double my = mean(y);
        double sxy = 0;
        double sxx = 0;
        double syy = 0;
        for (int i = 0; i < x.length; i++) {
            sxy += (x[i] - mx) * (y[i] - my);
            sxx += (x[i] - mx) * (x[i] - mx);
            syy += (y[i] - my) * (y[i] - my);
        }
        return sxy / Math.sqrt(sxx * syy);
    }

    private static double excessKurtosis(double[] x) {
        double m = mean(x);
        double s2 = 0;
        double s4 = 0;
        for (double v : x) {
            double d = v - m;
            s2 += d * d;
            s4 += d * d * d * d;
        }
        s2 /= x.length;
        s4 /= x.length;
        return s4 / (s2 * s2) - 3.0;
    }
}
