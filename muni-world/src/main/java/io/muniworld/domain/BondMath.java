package io.muniworld.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.function.DoubleUnaryOperator;

/**
 * Textbook fixed-income analytics for the bonds table — semiannual coupons, price per 100, clean.
 * These are DISPLAY economics computed deterministically from a bond's stored terms + price: current
 * yield, yield-to-maturity, yield-to-worst (min of YTM and yield-to-call), modified duration, convexity
 * and accrued interest. The exact-decimal discipline applies to money inputs (price/coupon, held as
 * {@code BigDecimal} on {@link Bond}); the iterative yield solve runs in {@code double} — analytics, not
 * money, and rounded for display. Full option-adjusted valuation of callable munis (curve + lattice, the
 * Kalotay north star) is a separate, deferred engine (ADR-0002), not this.
 */
public final class BondMath {

    private static final int FREQ = 2; // semiannual

    private BondMath() {
    }

    /** Annual coupon / price × 100. */
    public static double currentYield(double annualCouponPct, double price) {
        return price <= 0 ? Double.NaN : annualCouponPct / price * 100.0;
    }

    /** Yield (annual %, bond-equivalent) to a redemption of {@code redemption}/100 on {@code redeemDate}. */
    public static double yieldTo(double couponPct, LocalDate settle, LocalDate redeemDate,
                                 double redemption, double price) {
        int n = periods(settle, redeemDate);
        if (n <= 0 || price <= 0) {
            return Double.NaN;
        }
        double c = couponPct / FREQ; // coupon per period per 100
        DoubleUnaryOperator pvAt = yAnnual -> {
            double y = yAnnual / FREQ;
            double p = 0;
            for (int k = 1; k <= n; k++) {
                p += c / Math.pow(1 + y, k);
            }
            return p + redemption / Math.pow(1 + y, n);
        };
        // bisection: PV decreases in yield, so raise the low bound while PV still exceeds price
        double lo = 0.0, hi = 0.40;
        for (int i = 0; i < 100; i++) {
            double mid = (lo + hi) / 2;
            if (pvAt.applyAsDouble(mid) > price) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return (lo + hi) / 2 * 100.0;
    }

    public static double ytm(double couponPct, LocalDate settle, LocalDate maturity, double price) {
        return yieldTo(couponPct, settle, maturity, 100.0, price);
    }

    /** Yield-to-worst: min of yield-to-maturity and (if callable in the future) yield-to-call. */
    public static double ytw(double couponPct, LocalDate settle, LocalDate maturity, double price,
                             LocalDate callDate, double callPrice) {
        double ytm = ytm(couponPct, settle, maturity, price);
        if (callDate == null || !callDate.isAfter(settle)) {
            return ytm;
        }
        double ytc = yieldTo(couponPct, settle, callDate, callPrice <= 0 ? 100.0 : callPrice, price);
        return Math.min(ytm, ytc);
    }

    /** Modified duration (years) at YTM. */
    public static double modDuration(double couponPct, LocalDate settle, LocalDate maturity, double price) {
        int n = periods(settle, maturity);
        if (n <= 0) {
            return Double.NaN;
        }
        double y = ytm(couponPct, settle, maturity, price) / 100.0 / FREQ;
        double c = couponPct / FREQ;
        double pv = 0, weighted = 0;
        for (int k = 1; k <= n; k++) {
            double cf = c + (k == n ? 100.0 : 0.0);
            double d = cf / Math.pow(1 + y, k);
            pv += d;
            weighted += k * d;
        }
        double macaulayYears = (weighted / pv) / FREQ;
        return macaulayYears / (1 + y);
    }

    /** Convexity (years²) at YTM. */
    public static double convexity(double couponPct, LocalDate settle, LocalDate maturity, double price) {
        int n = periods(settle, maturity);
        if (n <= 0) {
            return Double.NaN;
        }
        double y = ytm(couponPct, settle, maturity, price) / 100.0 / FREQ;
        double c = couponPct / FREQ;
        double pv = 0, cx = 0;
        for (int k = 1; k <= n; k++) {
            double cf = c + (k == n ? 100.0 : 0.0);
            pv += cf / Math.pow(1 + y, k);
            cx += cf * k * (k + 1) / Math.pow(1 + y, k + 2);
        }
        return (cx / pv) / (FREQ * FREQ);
    }

    /** Accrued interest per 100 as of {@code settle}, coupons anchored on the maturity month/day (30/360). */
    public static double accrued(double couponPct, LocalDate settle, LocalDate maturity) {
        LocalDate prev = maturity;
        while (prev.isAfter(settle)) {
            prev = prev.minusMonths(6);
        }
        LocalDate next = prev.plusMonths(6);
        double periodDays = days30360(prev, next);
        if (periodDays <= 0) {
            return 0.0;
        }
        return couponPct / FREQ * (days30360(prev, settle) / periodDays);
    }

    // ---- helpers ----

    /** Whole semiannual periods between settlement and redemption (rounded). */
    static int periods(LocalDate settle, LocalDate redeem) {
        long months = ChronoUnit.MONTHS.between(settle.withDayOfMonth(1), redeem.withDayOfMonth(1));
        return (int) Math.max(0, Math.round(months / 6.0));
    }

    /** 30/360 (US) day count. */
    static int days30360(LocalDate d1, LocalDate d2) {
        int day1 = Math.min(d1.getDayOfMonth(), 30);
        int day2 = d2.getDayOfMonth();
        if (day1 == 30 && day2 == 31) {
            day2 = 30;
        }
        return (d2.getYear() - d1.getYear()) * 360
                + (d2.getMonthValue() - d1.getMonthValue()) * 30
                + (day2 - day1);
    }
}
