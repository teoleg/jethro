package io.jethro.trading.marketdata.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Curve factors and the duration link from curve to Treasury-futures prices. */
class CurveFactorSimulatorTest {

    @Test
    void sameSeedSameCurvePath() {
        var a = new CurveFactorSimulator(7, 0.038, 0.009);
        var b = new CurveFactorSimulator(7, 0.038, 0.009);
        for (int i = 0; i < 1_000; i++) {
            a.step();
            b.step();
        }
        assertEquals(a.zeroRate(10), b.zeroRate(10), 0);
    }

    @Test
    void curveShapeIsUpwardSlopingWithPositiveSlope() {
        var sim = new CurveFactorSimulator(7, 0.038, 0.009);
        assertTrue(sim.zeroRate(30) > sim.zeroRate(1), "positive slope → long end above short end");
    }

    @Test
    void linkedFuturesStartAtBaseAndFallWhenYieldsRise() {
        var sim = new CurveFactorSimulator(7, 0.038, 0.009);
        // At construction Δyield = 0 → price == base.
        assertEquals(110_500_000L, sim.linkedPriceScaled("ZN"));
        long before = sim.linkedPriceScaled("ZB");
        // Walk until the 30y yield has moved; check the duration link sign.
        double y0 = sim.zeroRate(30);
        for (int i = 0; i < 200_000 && Math.abs(sim.zeroRate(30) - y0) < 1e-4; i++) {
            sim.step();
        }
        long after = sim.linkedPriceScaled("ZB");
        boolean yieldsRose = sim.zeroRate(30) > y0;
        assertTrue(yieldsRose ? after < before : after > before,
                "bond futures must move opposite to yields (duration link)");
    }
}
