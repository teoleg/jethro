package io.jethro.trading.marketdata.sim;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The control (ADR-0031) drives the correlated simulator as designed AND — the load-bearing
 * property — an untouched control yields the exact same seeded tape as before the control
 * existed, so the panel never perturbs the deterministic baseline (ADR-0009).
 */
class SimControlAppliedTest {

    private static final double TICK_SECONDS = 0.1;
    private static final double SIM_SECONDS_PER_DAY = 120;
    private static final List<String> IDS = List.of("ES", "AAPL", "EURUSD");
    private static final long[] STARTS = {5_450_000_000L, 190_000_000L, 1_085_000L};

    private static final double[][] CORR = {
            {1.00, 0.30, 0.10, -0.30},
            {0.30, 1.00, -0.20, -0.10},
            {0.10, -0.20, 1.00, 0.00},
            {-0.30, -0.10, 0.00, 1.00}};

    private static FactorModelConfig cfg() {
        return new FactorModelConfig(0.16, 0.07, 5.0, 2.0, 5,
                List.of(new FactorModelConfig.InstrumentSpec("ES", 0.16, 1.0, 0.0),
                        new FactorModelConfig.InstrumentSpec("AAPL", 0.28, 1.2, 0.0),
                        new FactorModelConfig.InstrumentSpec("EURUSD", 0.08, 0.10, -1.0)),
                List.of(new FactorModelConfig.RegimeSpec("CALM", 0, 0, 0, 1.0, CORR)),
                new double[][]{{1.0}});
    }

    private static CorrelatedFactorSimulator plain() {
        return new CorrelatedFactorSimulator(42, cfg(), IDS, STARTS, TICK_SECONDS, SIM_SECONDS_PER_DAY);
    }

    private static CorrelatedFactorSimulator controlled(SimControl control) {
        return new CorrelatedFactorSimulator(42, cfg(), IDS, STARTS, TICK_SECONDS, SIM_SECONDS_PER_DAY, control);
    }

    @Test
    void untouchedControlReproducesTheSeededTapeExactly() {
        var baseline = plain();
        var withControl = controlled(new SimControl(1, IDS));
        for (int t = 0; t < 2_000; t++) {
            baseline.nextTick();
            withControl.nextTick();
            for (int i = 0; i < IDS.size(); i++) {
                assertEquals(baseline.priceScaled(i), withControl.priceScaled(i),
                        "tick " + t + " instrument " + i + " must match the no-control tape");
            }
        }
    }

    @Test
    void positiveDriftBiasPushesTheNameUpVersusBaseline() {
        var baseline = plain();
        var control = new SimControl(1, IDS);
        control.setDriftBias("ES", 0.001); // +10bp/tick log-drift on ES only
        var driven = controlled(control);
        for (int t = 0; t < 500; t++) {
            baseline.nextTick();
            driven.nextTick();
        }
        assertTrue(driven.priceScaled(0) > baseline.priceScaled(0),
                "positive drift must lift ES above the undriven tape");
        // AAPL/EURUSD share the same factor draws, but the ES idio path diverges once ES is
        // nudged, so we only assert the dialled name here.
    }

    @Test
    void volMultiplierWidensDispersion() {
        // AAPL has a large idiosyncratic component (annual vol 0.28 well above its systematic
        // 1.2×0.16), so scaling the idio dial visibly widens its dispersion.
        double baseVar = idioDispersion(1.0);
        double hotVar = idioDispersion(4.0);
        assertTrue(hotVar > baseVar * 2.0,
                "a 4x idio-vol dial must materially widen dispersion (" + hotVar + " vs " + baseVar + ")");
    }

    /** Sample stdev of AAPL tick log-returns at a given idio-vol dial. */
    private static double idioDispersion(double volDial) {
        int aapl = 1;
        var control = new SimControl(1, IDS);
        control.setVolMultiplier("AAPL", volDial);
        var sim = controlled(control);
        double prev = sim.priceScaled(aapl) / 1_000_000.0;
        double sum = 0;
        double sumSq = 0;
        int n = 2_000;
        for (int t = 0; t < n; t++) {
            sim.nextTick();
            double p = sim.priceScaled(aapl) / 1_000_000.0;
            double r = Math.log(p / prev);
            sum += r;
            sumSq += r * r;
            prev = p;
        }
        double mean = sum / n;
        return Math.sqrt(sumSq / n - mean * mean);
    }

    @Test
    void regimeOverrideHoldsTheForcedRegime() {
        var cfg = new FactorModelConfig(0.16, 0.07, 5.0, 2.0, 5,
                List.of(new FactorModelConfig.InstrumentSpec("ES", 0.16, 1.0, 0.0),
                        new FactorModelConfig.InstrumentSpec("AAPL", 0.28, 1.2, 0.0),
                        new FactorModelConfig.InstrumentSpec("EURUSD", 0.08, 0.10, -1.0)),
                List.of(new FactorModelConfig.RegimeSpec("CALM", 0, 0, 0, 1.0, CORR),
                        new FactorModelConfig.RegimeSpec("VOLATILE", 0, 0, 0, 3.0, CORR)),
                new double[][]{{0.99, 0.01}, {0.01, 0.99}});
        var control = new SimControl(1, IDS);
        control.overrideRegime(MarketRegime.VOLATILE);
        var sim = new CorrelatedFactorSimulator(7, cfg, IDS, STARTS, TICK_SECONDS, SIM_SECONDS_PER_DAY, control);
        for (int t = 0; t < 200; t++) {
            sim.nextTick();
            assertEquals(MarketRegime.VOLATILE, sim.regime(), "override must pin the regime at tick " + t);
        }
    }

    @Test
    void reseedResetsPricesToTheSeededStart() {
        var control = new SimControl(1, IDS);
        var sim = controlled(control);
        for (int t = 0; t < 300; t++) {
            sim.nextTick();
        }
        assertNotEquals(STARTS[0], sim.priceScaled(0), "prices should have moved off the start");
        control.reseed(123);
        sim.nextTick(); // reseed is consumed at the next tick, resetting prices before evolving
        // After reset the price evolves one tick from START, so it's near START, not the drifted level.
        long distanceFromStart = Math.abs(sim.priceScaled(0) - STARTS[0]);
        assertTrue(distanceFromStart < STARTS[0] / 100, "reseed must snap ES back near its seeded start");
    }
}
