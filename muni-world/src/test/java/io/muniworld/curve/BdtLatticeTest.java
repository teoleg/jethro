package io.muniworld.curve;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ADR-0018 lattice and pricer, held to hand-computed values and to the identities the construction
 * guarantees. A lattice that cannot reprice the zeros it calibrated to, or that fails its σ = 0 degeneracy,
 * would silently poison every OAS downstream — these tests are the tripwire.
 */
class BdtLatticeTest {

    /** Flat 5% continuous curve: P(0,t) = e^(−0.05t). */
    private static double[] flatDf(int n, double dt) {
        double[] df = new double[n];
        for (int i = 0; i < n; i++) {
            df[i] = Math.exp(-0.05 * (i + 1) * dt);
        }
        return df;
    }

    /** At σ = 0 the lattice collapses to the curve's forwards — a flat continuous curve has all rates 5%. */
    @Test
    void zeroVolLatticeIsTheForwardCurve() {
        BdtLattice l = BdtLattice.calibrate(flatDf(4, 0.5), 0.0, 0.5);
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j <= i; j++) {
                assertEquals(0.05, l.rate(i, j), 1e-9, "node (" + i + "," + j + ")");
            }
        }
    }

    /**
     * Calibration is exact by construction: whatever the σ, the lattice must reprice every input zero to
     * solver tolerance. Sloped curve, meaningful vol.
     */
    @Test
    void calibratedLatticeRepricesEveryInputZero() {
        NelsonSiegelSvensson curve = new NelsonSiegelSvensson(4.5, -1.5, 2.0, 1.0, 1.5, 8.0);
        int n = 20;
        double dt = 0.5;
        double[] df = new double[n];
        for (int i = 0; i < n; i++) {
            df[i] = curve.discountFactor((i + 1) * dt).doubleValue();
        }
        BdtLattice l = BdtLattice.calibrate(df, 0.20, dt);
        for (int i = 1; i <= n; i++) {
            assertEquals(df[i - 1], l.impliedDiscount(i), 1e-9, "zero maturing at step " + i);
        }
    }

    /**
     * The worked example (ADR-0018 Javadoc): flat 5% continuous curve, σ = 0, 1-year 6% semiannual bond,
     * 2 steps. price = 3·e^(−0.025) + 103·e^(−0.05)
     *              = 3·0.9753099120283326 + 103·0.951229424500714
     *              = 2.9259297360849977 + 97.97663072357354 = 100.90256045965854.
     */
    @Test
    void straightBondPriceMatchesTheHandComputedValue() {
        BdtLattice l = BdtLattice.calibrate(flatDf(2, 0.5), 0.0, 0.5);
        double price = LatticeBondPricer.price(l, 3.0, null, 0, 0.0);
        assertEquals(100.90256045965854, price, 1e-9);
    }

    /** A call the issuer would never exercise (strike far above value) must not change the price. */
    @Test
    void deepOutOfTheMoneyCallEqualsTheStraightBond() {
        BdtLattice l = BdtLattice.calibrate(flatDf(10, 0.5), 0.20, 0.5);
        double straight = LatticeBondPricer.price(l, 2.0, null, 0, 0.0);
        double callable = LatticeBondPricer.price(l, 2.0, 200.0, 1, 0.0);
        assertEquals(straight, callable, 1e-9);
    }

    /** The issuer's option can only hurt the holder: callable ≤ straight, strictly when the call bites. */
    @Test
    void callValueIsNeverNegativeAndBitesWhenInTheMoney() {
        // 8% coupon on a 5% curve → trades well above par → a par call is deep in the money.
        BdtLattice l = BdtLattice.calibrate(flatDf(10, 0.5), 0.15, 0.5);
        double straight = LatticeBondPricer.price(l, 4.0, null, 0, 0.0);
        double callable = LatticeBondPricer.price(l, 4.0, 100.0, 2, 0.0);
        assertTrue(straight > 105, "premise: the straight bond is a premium bond, got " + straight);
        assertTrue(callable < straight, "an in-the-money call must cost the holder value");
        assertTrue(callable >= 100.0 - 1e-9, "a par-callable bond cannot price below its first-call floor "
                + "on a positive-rate lattice, got " + callable);
    }

    /** More vol → the issuer's option is worth more → the callable bond is worth less. */
    @Test
    void callableValueDecreasesInVol() {
        double[] df = flatDf(20, 0.5);
        double low = LatticeBondPricer.price(BdtLattice.calibrate(df, 0.05, 0.5), 3.0, 100.0, 2, 0.0);
        double high = LatticeBondPricer.price(BdtLattice.calibrate(df, 0.30, 0.5), 3.0, 100.0, 2, 0.0);
        assertTrue(high < low, "σ 30% price " + high + " must be below σ 5% price " + low);
        // ...while the STRAIGHT bond is vol-insensitive up to lattice convexity noise.
        double sLow = LatticeBondPricer.price(BdtLattice.calibrate(df, 0.05, 0.5), 3.0, null, 0, 0.0);
        double sHigh = LatticeBondPricer.price(BdtLattice.calibrate(df, 0.30, 0.5), 3.0, null, 0, 0.0);
        assertEquals(sLow, sHigh, 0.05, "a straight bond's value comes from the curve, not σ");
    }

    /**
     * The OAS round trip — the solver's defining property: price a bond AT a known spread, hand that price
     * to the solver, get the spread back. 100bp in, 100bp out (to bisection tolerance).
     */
    @Test
    void oasRoundTripRecoversTheKnownSpread() {
        BdtLattice l = BdtLattice.calibrate(flatDf(20, 0.5), 0.15, 0.5);
        for (double spread : new double[] {0.0100, -0.0035, 0.0} ) {
            double dirty = LatticeBondPricer.price(l, 2.5, 100.0, 4, spread);
            double solved = LatticeBondPricer.solveOas(l, 2.5, 100.0, 4, dirty);
            assertEquals(spread, solved, 1e-8, "round trip at spread " + spread);
        }
    }

    /** A price no spread in ±1,000bp can reach is reported as NaN — never clamped and presented as a fit. */
    @Test
    void unreachablePriceReportsUnsolvableInsteadOfClamping() {
        BdtLattice l = BdtLattice.calibrate(flatDf(4, 0.5), 0.15, 0.5);
        assertTrue(Double.isNaN(LatticeBondPricer.solveOas(l, 2.5, null, 0, 400.0)));
        assertTrue(Double.isNaN(LatticeBondPricer.solveOas(l, 2.5, null, 0, 5.0)));
    }

    /** Positive spread lowers price; the relation the bisection relies on. */
    @Test
    void priceIsMonotoneDecreasingInSpread() {
        BdtLattice l = BdtLattice.calibrate(flatDf(10, 0.5), 0.15, 0.5);
        double p0 = LatticeBondPricer.price(l, 2.5, 100.0, 2, 0.0);
        double p1 = LatticeBondPricer.price(l, 2.5, 100.0, 2, 0.0050);
        double p2 = LatticeBondPricer.price(l, 2.5, 100.0, 2, 0.0100);
        assertTrue(p0 > p1 && p1 > p2, p0 + " > " + p1 + " > " + p2 + " expected");
    }
}
