package io.jethro.trading.marketdata.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real Treasury curve as a {@link CurveMarkSource}: exact tenor marks, linear interpolation in
 * year-space, and par-rate sanity — the same emission shape as the sim, just on real levels.
 */
class RealTreasuryCurveTest {

    // Nodes aligned to CurveMarkSource.TENORS = {1,2,5,10,30}Y, in fraction.
    private static final double[] ZEROS = {0.050, 0.048, 0.045, 0.043, 0.044};

    @Test
    void tenorMarksSplitIntoTsyRawAndSofrMinusSpread() {
        var c = new RealTreasuryCurve(ZEROS);
        // The raw data IS the Treasury par curve...
        assertEquals(5_000_000L, c.tsyRateScaledPercent(0), "TSY 1Y = 5.00% (raw)");
        assertEquals(4_400_000L, c.tsyRateScaledPercent(4), "TSY 30Y = 4.40% (raw)");
        // ...and the SOFR proxy sits BELOW it by the stylized swap spread.
        assertEquals(4_920_000L, c.rateScaledPercent(0), "SOFR 1Y = 5.00% - 8bp");
        assertEquals(4_650_000L, c.rateScaledPercent(1), "SOFR 2Y = 4.80% - 15bp");
        assertEquals(4_250_000L, c.rateScaledPercent(2), "SOFR 5Y = 4.50% - 25bp");
        assertEquals(3_920_000L, c.rateScaledPercent(3), "SOFR 10Y = 4.30% - 38bp");
        assertEquals(3_750_000L, c.rateScaledPercent(4), "SOFR 30Y = 4.40% - 65bp");
    }

    @Test
    void interpolatesLinearlyBetweenNodesAndFlatBeyondEnds() {
        var c = new RealTreasuryCurve(ZEROS);
        assertEquals(0.050, c.zeroRate(1), 1e-12, "on the first node");
        assertEquals(0.044, c.zeroRate(30), 1e-12, "on the last node");
        // 7.5Y is halfway between 5Y (4.5%) and 10Y (4.3%) → 4.4%.
        assertEquals(0.044, c.zeroRate(7.5), 1e-12, "midpoint 5Y–10Y");
        // Flat extrapolation beyond the ends.
        assertEquals(0.050, c.zeroRate(0.25), 1e-12, "below the short end → short zero");
        assertEquals(0.044, c.zeroRate(40), 1e-12, "beyond the long end → long zero");
    }

    @Test
    void swapParRateSitsNearTheSofrLevelBelowTreasuries() {
        var c = new RealTreasuryCurve(ZEROS);
        long par5y = c.swapParScaledPercent(0); // 5Y
        // Swaps discount on the SOFR proxy (TSY − spread ≈ 4.92→4.25% over 1-5Y) → par ~4.4%.
        assertTrue(par5y > 4_200_000L && par5y < 4_700_000L, "5Y par near the SOFR level: " + par5y);
        // And BELOW the 5Y Treasury yield — that's the negative swap spread by construction.
        assertTrue(par5y < c.tsyRateScaledPercent(2), "5Y swap par below the 5Y TSY yield");
        assertTrue(c.swapParScaledPercent(1) > 3_800_000L, "10Y par positive and plausible");
    }

    @Test
    void stepIsANoOpAndUpdateReplacesLevels() {
        var c = new RealTreasuryCurve(ZEROS);
        c.step(MarketRegime.VOLATILE, 1); // real curve ignores ticks
        assertEquals(5_000_000L, c.tsyRateScaledPercent(0), "unchanged by step()");
        c.update(new double[]{0.030, 0.031, 0.033, 0.035, 0.036});
        assertEquals(3_000_000L, c.tsyRateScaledPercent(0), "TSY 1Y now 3.00% after update");
        assertEquals(3_600_000L, c.tsyRateScaledPercent(4), "TSY 30Y now 3.60%");
    }

    @Test
    void malformedUpdatesAreIgnoredAndNegativesFloored() {
        var c = new RealTreasuryCurve(ZEROS);
        c.update(null);
        c.update(new double[]{0.04, 0.04}); // wrong length
        assertEquals(5_000_000L, c.tsyRateScaledPercent(0), "kept the last good curve");
        c.update(new double[]{-0.01, 0.02, 0.03, 0.04, 0.05}); // negative short rate
        assertEquals(10_000L, c.tsyRateScaledPercent(0), "negative floored to 1bp (0.01%)");
    }

    @Test
    void isNotCurveLinked() {
        // A live feed prices Treasury futures from the market, not from the curve.
        assertTrue(!new RealTreasuryCurve(ZEROS).isLinked("ZN"));
    }
}
