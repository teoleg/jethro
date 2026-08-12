package io.muniworld.curve;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The book-level analytics (ADR-0018 / ModelAnalytics), held to identities that can be reasoned by hand —
 * the same discipline as the lattice tests: no assertion pins a value that was merely observed.
 */
class ModelAnalyticsTest {

    private static double[] flatDf(int n, double dt, double y) {
        double[] df = new double[n];
        for (int i = 0; i < n; i++) {
            df[i] = Math.exp(-y * (i + 1) * dt);
        }
        return df;
    }

    /**
     * A 10-year ZERO-COUPON bullet on a flat continuous curve has effective duration ≈ 10 years and
     * convexity ≈ 100 (t and t² — the continuous-compounding identities). The bump method must land on
     * them to first order; tolerance covers the finite 25bp difference quotient.
     */
    @Test
    void zeroCouponDurationIsItsMaturityAndConvexityItsSquare() {
        ModelAnalytics.Result r = ModelAnalytics.analyze(
                flatDf(20, 0.5, 0.05), 0.0, 0.5, 0.0, null, 0, false, 0.0);
        assertEquals(10.0, r.effDuration(), 0.01, "duration of a 10y zero must be ~10y");
        assertEquals(100.0, r.effConvexity(), 0.5, "convexity of a 10y zero must be ~t² = 100");
        assertEquals(Math.exp(-0.5) * 100, r.straight(), 1e-6, "P = 100·e^(−0.05·10)");
    }

    /** No call specified: the legs coincide, option value is exactly zero, efficiency is absent. */
    @Test
    void bulletHasZeroOptionValue() {
        ModelAnalytics.Result r = ModelAnalytics.analyze(
                flatDf(20, 0.5, 0.04), 0.15, 0.5, 2.5, null, 0, false, 0.0);
        assertEquals(r.straight(), r.callable(), 1e-12);
        assertEquals(0.0, r.optionValue(), 1e-12);
        assertNull(r.refundingEfficiency());
    }

    /**
     * The book's central risk picture — with the moneyness that actually produces it. NEGATIVE convexity
     * lives NEAR the money, where a rate move flips the call decision (4% coupon on a 3% curve, 5 years of
     * call protection → conv ≈ −240 measured). DEEP in the money the call is certain, the bond collapses
     * to a short bullet and convexity goes back to small-positive — the first version of this test had
     * that premise wrong, and the numeric probe across coupons is what corrected it.
     */
    @Test
    void nearTheCallDurationShortensAndConvexityGoesNegative() {
        double[] df = flatDf(40, 0.5, 0.03);
        // near the money: 4% coupon, callable from step 10 (5y) at par
        ModelAnalytics.Result callable = ModelAnalytics.analyze(df, 0.15, 0.5, 2.0, 100.0, 10, false, 0.0);
        ModelAnalytics.Result bullet = ModelAnalytics.analyze(df, 0.15, 0.5, 2.0, null, 0, false, 0.0);
        assertTrue(callable.effDuration() < bullet.effDuration() * 0.8,
                "callable duration " + callable.effDuration() + " must shorten vs bullet " + bullet.effDuration());
        assertTrue(callable.effConvexity() < 0,
                "near-the-money callable must show negative convexity, got " + callable.effConvexity());
        assertTrue(bullet.effConvexity() > 0, "the bullet stays positively convex");
        // deep in the money: certain call → a ~1y bullet in disguise: SHORT duration, convexity back >= 0
        ModelAnalytics.Result deep = ModelAnalytics.analyze(df, 0.15, 0.5, 3.0, 100.0, 2, false, 0.0);
        assertTrue(deep.effDuration() < 2, "a certain near call prices like a short bullet");
    }

    /**
     * Refunding efficiency approaches 100% when the option's time value is exhausted: a currently-callable
     * bond deep in the money (6% coupon, 3% rates) at LOW vol has almost no time value left, so
     * (straight − K) ≈ (straight − callable) and efficiency → ~100%. At HIGH vol the alive option carries
     * more time value, so efficiency must be LOWER — the exercise-now case weakens.
     */
    @Test
    void refundingEfficiencyIsNearOneDeepInTheMoneyAndFallsWithVol() {
        double[] df = flatDf(40, 0.5, 0.03);
        ModelAnalytics.Result lowVol = ModelAnalytics.analyze(df, 0.05, 0.5, 3.0, 100.0, 1, true, 0.0);
        ModelAnalytics.Result highVol = ModelAnalytics.analyze(df, 0.30, 0.5, 3.0, 100.0, 1, true, 0.0);
        assertNotNull(lowVol.refundingEfficiency());
        assertNotNull(highVol.refundingEfficiency());
        assertTrue(lowVol.refundingEfficiency() > 0.95,
                "deep ITM at low vol must be ~100%, got " + lowVol.refundingEfficiency());
        assertTrue(highVol.refundingEfficiency() < lowVol.refundingEfficiency(),
                "more vol = more time value alive = lower efficiency");
    }

    /** Out of the money (coupon below rates), calling now destroys value: efficiency must be NEGATIVE. */
    @Test
    void refundingEfficiencyIsNegativeOutOfTheMoney() {
        // 3% coupon on a 5% curve → straight worth well under par → straight − 100 < 0.
        double[] df = flatDf(40, 0.5, 0.05);
        ModelAnalytics.Result r = ModelAnalytics.analyze(df, 0.20, 0.5, 1.5, 100.0, 1, true, 0.0);
        assertNotNull(r.refundingEfficiency());
        assertTrue(r.refundingEfficiency() < 0,
                "calling a discount bond destroys value, got " + r.refundingEfficiency());
    }

    /** Not currently callable → efficiency is ABSENT (the decision does not exist yet), never a number. */
    @Test
    void efficiencyIsAbsentBeforeTheCallWindowOpens() {
        double[] df = flatDf(40, 0.5, 0.03);
        ModelAnalytics.Result r = ModelAnalytics.analyze(df, 0.15, 0.5, 3.0, 100.0, 10, false, 0.0);
        assertTrue(r.optionValue() > 0, "the option still has value…");
        assertNull(r.refundingEfficiency(), "…but efficiency is not defined before the window opens");
    }
}
