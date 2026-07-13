package io.jethro.trading.marketdata.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Curve factors, regime coupling, and the duration link from curve to futures prices. */
class CurveFactorSimulatorTest {

    @Test
    void sameSeedSameCurvePath() {
        var a = new CurveFactorSimulator(7, 0.038, 0.009);
        var b = new CurveFactorSimulator(7, 0.038, 0.009);
        for (int i = 0; i < 1_000; i++) {
            a.step();
            b.step();
        }
        // Regime-coupled steps (vol multiple, drift, shock) must stay deterministic too.
        for (int i = 0; i < 1_000; i++) {
            a.step(MarketRegime.VOLATILE, i == 500 ? 1 : 0);
            b.step(MarketRegime.VOLATILE, i == 500 ? 1 : 0);
        }
        assertEquals(a.zeroRate(10), b.zeroRate(10), 0);
        assertEquals(a.linkedPriceScaled("ZN"), b.linkedPriceScaled("ZN"));
        assertEquals(a.swapParScaledPercent(0), b.swapParScaledPercent(0));
    }

    @Test
    void curveShapeIsUpwardSlopingWithPositiveSlope() {
        var sim = new CurveFactorSimulator(7, 0.038, 0.009);
        assertTrue(sim.zeroRate(30) > sim.zeroRate(1), "positive slope → long end above short end");
    }

    @Test
    void linkedFuturesStartAtBaseAndFallWhenYieldsRise() {
        var sim = new CurveFactorSimulator(7, 0.038, 0.009);
        // At construction Δyield = 0 and basis = 0 → price == base.
        assertEquals(110_500_000L, sim.linkedPriceScaled("ZN"));
        // A deliberate 30bp selloff (shocks up, no noise ambiguity) must sink the future
        // far beyond what basis noise (~0.3bp stationary σ) could offset.
        long before = sim.linkedPriceScaled("ZB");
        double y0 = sim.zeroRate(30);
        for (int i = 0; i < 8; i++) {
            sim.step(MarketRegime.CALM, 1); // each shock jumps the level +2..6bp
        }
        assertTrue(sim.zeroRate(30) > y0 + 0.001, "shocks must have lifted yields");
        assertTrue(sim.linkedPriceScaled("ZB") < before,
                "bond futures must move opposite to yields (duration link)");
    }

    @Test
    void trendRegimeDriftsTheLevel() {
        var sim = new CurveFactorSimulator(7, 0.038, 0.009);
        double y0 = sim.zeroRate(10);
        // TREND_UP drift is ~0.045e-6/tick; over 20k ticks that is ~9bp against a noise
        // σ of ~1.2bp — the trend must dominate (this is what momentum then catches).
        for (int i = 0; i < 20_000; i++) {
            sim.step(MarketRegime.TREND_UP, 0);
        }
        assertTrue(sim.zeroRate(10) > y0 + 0.0004, "a rates trend must move yields visibly");
    }

    @Test
    void volatileRegimeAllowsBiggerFactorSteps() {
        var sim = new CurveFactorSimulator(7, 0.038, 0.009);
        double maxCalm = 0;
        double maxVol = 0;
        for (int i = 0; i < 5_000; i++) {
            double before = sim.zeroRate(30); // 30y ≈ level + slope (loading ~1)
            sim.step(MarketRegime.CALM, 0);
            maxCalm = Math.max(maxCalm, Math.abs(sim.zeroRate(30) - before));
        }
        for (int i = 0; i < 5_000; i++) {
            double before = sim.zeroRate(30);
            sim.step(MarketRegime.VOLATILE, 0);
            maxVol = Math.max(maxVol, Math.abs(sim.zeroRate(30) - before));
        }
        assertTrue(maxVol > maxCalm, "VOLATILE (vol ×3) must produce larger steps than CALM");
    }

    @Test
    void swapParRateSitsOnTheCurve() {
        var sim = new CurveFactorSimulator(7, 0.038, 0.009);
        // par = (1 − DF(n)) / Σ DF(i): for an upward-sloping curve the 5y par sits
        // between the 1y and 5y zeros. Quoted in percent, scaled 1e-6.
        double par5 = sim.swapParScaledPercent(0) / 1_000_000.0 / 100.0;
        assertTrue(par5 > sim.zeroRate(1) && par5 < sim.zeroRate(5) + 0.001,
                "5y par must sit within the curve, got " + par5);
        double par10 = sim.swapParScaledPercent(1) / 1_000_000.0 / 100.0;
        assertTrue(par10 > par5, "upward curve → 10y par above 5y par");
    }
}
