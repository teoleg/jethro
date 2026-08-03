package io.muniworld.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sanity anchors for the bond math: a par bond yields its coupon, a premium callable's yield-to-worst is
 * the yield-to-call (below YTM), current yield is coupon/price, and duration/accrued land in sane ranges.
 */
class BondMathTest {

    private static final LocalDate SETTLE = LocalDate.of(2025, 6, 1);

    @Test
    void parBondYieldsItsCoupon() {
        double ytm = BondMath.ytm(5.0, SETTLE, LocalDate.of(2035, 6, 1), 100.0);
        assertEquals(5.0, ytm, 0.05, "a bond priced at par yields ~its coupon");
        assertEquals(5.0, BondMath.currentYield(5.0, 100.0), 1e-9);
    }

    @Test
    void premiumBondYieldsBelowCoupon() {
        double ytm = BondMath.ytm(5.0, SETTLE, LocalDate.of(2040, 6, 1), 108.0);
        assertTrue(ytm > 0 && ytm < 5.0, "a premium bond yields below its coupon, got " + ytm);
    }

    @Test
    void yieldToWorstIsTheCallForAPremiumCallable() {
        LocalDate mat = LocalDate.of(2040, 6, 1);
        LocalDate call = LocalDate.of(2032, 6, 1);
        double ytm = BondMath.ytm(5.0, SETTLE, mat, 108.0);
        double ytw = BondMath.ytw(5.0, SETTLE, mat, 108.0, call, 100.0);
        assertTrue(ytw <= ytm + 1e-9, "YTW never exceeds YTM (ytw=" + ytw + ", ytm=" + ytm + ")");
        assertTrue(ytw < ytm, "for a premium callable the call is the worst case");
    }

    @Test
    void durationAndAccruedAreSane() {
        double dur = BondMath.modDuration(5.0, SETTLE, LocalDate.of(2035, 6, 1), 100.0);
        assertTrue(dur > 5 && dur < 9, "10y 5% par mod duration ~7.7y, got " + dur);
        // settle 3 months after a Jun-1 coupon (coupons Jun/Dec) → ~1/2 of a semiannual 2.5% coupon
        double acc = BondMath.accrued(5.0, LocalDate.of(2025, 9, 1), LocalDate.of(2035, 6, 1));
        assertTrue(acc > 0 && acc < 2.5, "accrued within a coupon period, got " + acc);
    }
}
