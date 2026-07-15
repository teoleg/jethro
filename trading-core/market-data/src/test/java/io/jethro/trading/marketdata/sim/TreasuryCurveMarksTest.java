package io.jethro.trading.marketdata.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sim carries BOTH rates curves (user requirement: the sim supports all required data):
 * SOFR (swaps discount here) and the DISTINCT US Treasury par curve (futures key here), with
 * the swap spread between them — stylized base + stochastic basis in the factor sim; and for
 * the real Treasury feed the derivation runs the other way (SOFR = TSY − spread).
 */
class TreasuryCurveMarksTest {

    @Test
    void simTsyCurveSitsAboveSofrByTheSwapSpread() {
        var sim = new CurveFactorSimulator(7, 0.038, 0.009);
        for (int i = 0; i < 100; i++) {
            sim.step();
        }
        for (int t = 0; t < CurveMarkSource.TENORS.length; t++) {
            long sofr = sim.rateScaledPercent(t);
            long tsy = sim.tsyRateScaledPercent(t);
            double spreadBp = (tsy - sofr) / 1_000_000.0 * 100; // scaled-% diff → bp
            double base = CurveMarkSource.TSY_SPREAD_BP[t];
            assertTrue(Math.abs(spreadBp - base) < 5,
                    "tenor " + t + ": TSY−SOFR spread " + spreadBp + "bp should sit near the base "
                            + base + "bp (stochastic basis is small)");
            assertTrue(tsy > sofr, "TSY par above the SOFR swap rate (negative-swap-spread era)");
        }
    }

    @Test
    void realCurveDerivesSofrBelowItsTreasuryData() {
        // Real feed: raw data IS Treasury (4.85/4.60/4.40/4.35/4.50) — SOFR = TSY − spread.
        var curve = new RealTreasuryCurve(new double[]{0.0485, 0.0460, 0.0440, 0.0435, 0.0450});
        assertEquals(4_850_000L, curve.tsyRateScaledPercent(0), "TSY node is the raw datum");
        assertEquals(4_770_000L, curve.rateScaledPercent(0), "SOFR 1Y = 4.85% − 8bp = 4.77%");
        assertEquals(3_850_000L, curve.rateScaledPercent(4), "SOFR 30Y = 4.50% − 65bp = 3.85%");
    }
}
