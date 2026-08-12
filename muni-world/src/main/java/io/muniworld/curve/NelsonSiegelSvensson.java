package io.muniworld.curve;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The Nelson-Siegel-Svensson zero-coupon yield curve (ADR-0017) — the functional form the Federal Reserve
 * publishes its Treasury curve in (GSW / FEDS 2006-28, {@code feds200628.csv}), so six parameters per day
 * reconstruct the whole zero curve at any maturity, from 1961 to the present, for free.
 *
 * <p>Continuously-compounded zero yield at maturity {@code n} years:
 *
 * <pre>
 *   y(n) = B0
 *        + B1 · [ (1 − e^(−n/T1)) / (n/T1) ]
 *        + B2 · [ (1 − e^(−n/T1)) / (n/T1) − e^(−n/T1) ]
 *        + B3 · [ (1 − e^(−n/T2)) / (n/T2) − e^(−n/T2) ]
 * </pre>
 *
 * B0 is the long-run level, B1 the slope (so {@code y(0) = B0 + B1}, the instantaneous short rate), B2 and
 * B3 two humps with decay speeds T1 and T2. Parameters in the Fed's file are in PERCENT.
 *
 * <p><b>Worked example</b> (encoded as a test): B0 = 4.0, B1 = −1.0, B2 = B3 = 0, T1 = T2 = 1, n = 1.
 * Then n/T1 = 1, so the slope loading is (1 − e^(−1))/1 = 1 − 0.367879441 = 0.632120559, and
 * y(1) = 4.0 + (−1.0)(0.632120559) = 3.367879441 % → 0.03367879 as a rate at 8dp.
 *
 * <p><b>Exactness boundary (ADR-0017 §4).</b> This is transcendental mathematics — {@code exp()} has no
 * exact {@code BigDecimal} form — so the FIT is computed in {@code double} and its result is rounded ONCE,
 * at a declared scale, into {@code BigDecimal}. Everything downstream of {@link #zeroRate} (discounting,
 * cashflows, price, PnL) is exact decimal, per invariant 1. Nothing here is a money amount.
 */
public final class NelsonSiegelSvensson {

    /** Zero rates are stored/returned at 1e-8 — 1/10000 of a basis point, far finer than any quoted rate. */
    public static final int RATE_SCALE = 8;

    /** Below this maturity the loadings are evaluated at their n→0 limit rather than dividing by ~zero. */
    private static final double NEAR_ZERO_YEARS = 1e-9;

    private final double beta0;
    private final double beta1;
    private final double beta2;
    private final double beta3;
    private final double tau1;
    private final double tau2;

    /**
     * @param beta0 level, in percent
     * @param beta1 slope, in percent
     * @param beta2 first hump, in percent
     * @param beta3 second hump, in percent — 0 for the early history, which the Fed fits as plain
     *              Nelson-Siegel (no fourth term); passing 0 with any {@code tau2} is exactly that model
     * @param tau1  first decay, in years — must be positive
     * @param tau2  second decay, in years — must be positive when {@code beta3 != 0}
     */
    public NelsonSiegelSvensson(double beta0, double beta1, double beta2, double beta3,
                                double tau1, double tau2) {
        if (!(tau1 > 0)) {
            throw new IllegalArgumentException("tau1 must be positive, got " + tau1);
        }
        if (beta3 != 0 && !(tau2 > 0)) {
            throw new IllegalArgumentException("tau2 must be positive when beta3 is non-zero, got " + tau2);
        }
        this.beta0 = beta0;
        this.beta1 = beta1;
        this.beta2 = beta2;
        this.beta3 = beta3;
        this.tau1 = tau1;
        this.tau2 = tau2;
    }

    /**
     * The continuously-compounded zero yield at {@code years}, as a RATE (0.0425 = 4.25%), exact-decimal at
     * {@link #RATE_SCALE}. Rounding is HALF_UP and explicit — an unspecified rounding is a bug.
     */
    public BigDecimal zeroRate(double years) {
        if (years < 0) {
            throw new IllegalArgumentException("maturity must be non-negative, got " + years);
        }
        double percent = beta0
                + beta1 * levelLoading(years, tau1)
                + beta2 * humpLoading(years, tau1)
                + (beta3 == 0 ? 0 : beta3 * humpLoading(years, tau2));
        return BigDecimal.valueOf(percent)
                .movePointLeft(2)                       // percent → rate
                .setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * The discount factor {@code e^(−y(n)·n)} for a cashflow {@code years} out, at 12dp. Continuous
     * compounding is the convention the GSW zero curve is published in — using it directly avoids a
     * compounding-frequency conversion that would otherwise be an unstated assumption.
     */
    public BigDecimal discountFactor(double years) {
        double y = zeroRate(years).doubleValue();
        return BigDecimal.valueOf(Math.exp(-y * years)).setScale(12, RoundingMode.HALF_UP);
    }

    /** {@code (1 − e^(−x))/x} with x = n/tau — the slope loading. Limit 1 as n→0. */
    private static double levelLoading(double years, double tau) {
        if (years < NEAR_ZERO_YEARS) {
            return 1.0;
        }
        double x = years / tau;
        return (1 - Math.exp(-x)) / x;
    }

    /** {@code (1 − e^(−x))/x − e^(−x)} — the hump loading. Limit 0 as n→0. */
    private static double humpLoading(double years, double tau) {
        if (years < NEAR_ZERO_YEARS) {
            return 0.0;
        }
        double x = years / tau;
        return (1 - Math.exp(-x)) / x - Math.exp(-x);
    }

    /** The instantaneous short rate {@code y(0) = B0 + B1} — the lattice's starting node. */
    public BigDecimal shortRate() {
        return zeroRate(0);
    }

    @Override
    public String toString() {
        return "NSS[b0=" + beta0 + " b1=" + beta1 + " b2=" + beta2 + " b3=" + beta3
                + " t1=" + tau1 + " t2=" + tau2 + "]";
    }
}
