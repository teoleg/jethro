package io.jethro.trading.marketdata.sim;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hard session price band (reflecting): no combination of dials can run a price to absurdity —
 * a maxed drift fader compounds 100bp/tick, and without the band would multiply a price ~22,000×
 * in 1,000 ticks. With the band it stays within [start/4, start·4], and the tape never pins to
 * the wall (reflection keeps it moving).
 */
class PriceBandTest {

    private static final List<String> IDS = List.of("ES");
    private static final long[] STARTS = {100_000_000L}; // 100.000000

    private static FactorModelConfig cfg() {
        double[][] corr = {
                {1.00, 0.30, 0.10, -0.30},
                {0.30, 1.00, -0.20, -0.10},
                {0.10, -0.20, 1.00, 0.00},
                {-0.30, -0.10, 0.00, 1.00}};
        return new FactorModelConfig(0.16, 0.07, 5.0, 2.0, 5,
                List.of(new FactorModelConfig.InstrumentSpec("ES", 0.16, 1.0, 0.0)),
                List.of(new FactorModelConfig.RegimeSpec("CALM", 0, 0, 0, 1.0, corr)),
                new double[][]{{1.0}});
    }

    @Test
    void maxedDriftCannotEscapeTheBand() {
        SimControl control = new SimControl(1, IDS);
        control.setDriftBias("ES", 0.01); // the clamp maximum: +100bp/tick, compounding
        var sim = new CorrelatedFactorSimulator(42, cfg(), IDS, STARTS, 0.1, 23_400, control);
        double cap = 100.0 * CorrelatedFactorSimulator.DEFAULT_PRICE_BAND;
        for (int t = 0; t < 5_000; t++) {
            sim.nextTick();
            double px = sim.priceScaled(0) / 1e6;
            assertTrue(px <= cap + 1e-6, "tick " + t + ": price " + px + " escaped the cap " + cap);
            assertTrue(px > 0, "price must stay positive");
        }
        // The band reflects rather than pins: with drift still pushing up, the price sits NEAR the
        // cap but the tape keeps printing distinct values (no flatline at the wall).
        double a = sim.priceScaled(0) / 1e6;
        sim.nextTick();
        double b = sim.priceScaled(0) / 1e6;
        assertTrue(a != b, "reflection must keep the tape moving, not pin it to the band");
    }

    @Test
    void stackedNudgesReflectBackInside() {
        SimControl control = new SimControl(1, IDS);
        var sim = new CorrelatedFactorSimulator(42, cfg(), IDS, STARTS, 0.1, 23_400, control);
        for (int k = 0; k < 10; k++) {
            control.nudge("ES", 5.0); // fat-finger clamp max: ×6 per nudge, stacked 10 deep
            sim.nextTick();
            double px = sim.priceScaled(0) / 1e6;
            assertTrue(px <= 400.0 + 1e-6 && px >= 25.0 - 1e-6,
                    "nudge " + k + ": price " + px + " left the [25, 400] band");
        }
    }

    @Test
    void bandDisabledLeavesTheTapeUnbounded() {
        SimControl control = new SimControl(1, IDS);
        control.setDriftBias("ES", 0.01);
        var sim = new CorrelatedFactorSimulator(42, cfg(), IDS, STARTS, 0.1, 23_400, control, 0);
        for (int t = 0; t < 1_000; t++) {
            sim.nextTick();
        }
        assertTrue(sim.priceScaled(0) / 1e6 > 400.0,
                "band ≤1 must disable the guard (compounded drift escapes)");
    }

    @Test
    void bandedRunMatchesUnbandedWhilePricesStayInside() {
        // The band must be a pure no-op until touched: identical seeds, calm tape → identical prices.
        var banded = new CorrelatedFactorSimulator(7, cfg(), IDS, STARTS, 0.1, 23_400);
        var unbanded = new CorrelatedFactorSimulator(7, cfg(), IDS, STARTS, 0.1, 23_400,
                new SimControl(1, IDS), 0);
        for (int t = 0; t < 2_000; t++) {
            banded.nextTick();
            unbanded.nextTick();
            assertEquals(unbanded.priceScaled(0), banded.priceScaled(0),
                    "tick " + t + ": the band must not perturb an in-band tape");
        }
    }
}
