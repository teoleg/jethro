package io.muniworld.curve;

/**
 * Prices a fixed-coupon (optionally callable) bond on a {@link BdtLattice} and solves the OAS (ADR-0018).
 *
 * <p>Cash-flow model: semiannual-style coupons of {@code coupon/2 per 100} at every step time t_1..t_n,
 * redemption 100 at t_n. The issuer's call is American on/after the first lattice step at or past the call
 * date, exercised to MINIMISE holder value: {@code V = min(continuation, callPrice) + coupon} at each
 * callable step. OAS is a continuously-compounded spread added to every node rate; the solve is bisection
 * within ±1,000bp.
 *
 * <p>Worked example (encoded as a test): flat 5% continuous curve, σ = 0, 1-year bond, 6% coupon, 2 steps.
 * σ = 0 collapses the lattice to the curve's forwards, so
 * price = 3·e^(−0.05·0.5) + 103·e^(−0.05·1.0) = 3·0.975310 + 103·0.951229 = 100.902560.
 *
 * <p>Pure floating-point per the ADR-0017 §4 boundary — the caller rounds once at a declared scale.
 */
public final class LatticeBondPricer {

    /**
     * OAS solve bounds: ±1,000bp as a continuous spread. An implementer-chosen range (Tier J,
     * docs/model-assumptions.md): wide enough for any performing muni, and a price outside it returns
     * "unsolvable" — reported, never clamped to a bound and presented as a fit. Widening the range only
     * changes which marks get an answer instead of a refusal; it never changes a solved value.
     */
    public static final double MAX_SPREAD = 0.10;

    private LatticeBondPricer() {
    }

    /**
     * Value per 100 at time 0.
     *
     * @param lattice       calibrated lattice; its step count defines the bond's maturity t_n
     * @param couponPerStep coupon paid at each step time, per 100 (e.g. 2.5 for a 5% semiannual bond)
     * @param callPrice     call strike per 100, or {@code null} for a non-callable bond
     * @param firstCallStep first step index (1-based cash-flow time) at which the issuer may call;
     *                      ignored when callPrice is null
     * @param spread        continuously-compounded spread added to every node rate (the OAS candidate)
     */
    public static double price(BdtLattice lattice, double couponPerStep, Double callPrice,
                               int firstCallStep, double spread) {
        int n = lattice.steps();
        // Value AT maturity, after the final coupon and redemption are received.
        double[] value = new double[n + 1];
        java.util.Arrays.fill(value, 100.0 + couponPerStep);
        for (int i = n - 1; i >= 0; i--) {
            double[] next = new double[i + 1];
            for (int j = 0; j <= i; j++) {
                double df = Math.exp(-(lattice.rate(i, j) + spread) * lattice.dt());
                double cont = df * 0.5 * (value[j] + value[j + 1]);
                double coupon = i > 0 ? couponPerStep : 0.0;   // t_0 is settlement, no flow
                // The call decision applies to the FORWARD value (continuation), coupon already earned.
                if (callPrice != null && i >= firstCallStep && i > 0 && cont > callPrice) {
                    cont = callPrice;
                }
                next[j] = cont + coupon;
            }
            value = next;
        }
        return value[0];
    }

    /**
     * The OAS that reprices {@code targetClean} — bisection on the monotone price/spread relation.
     * Returns {@code NaN} when the target is outside what ±1,000bp can reach (reported by the caller as
     * unsolvable, never clamped to a bound and presented as a fit).
     */
    public static double solveOas(BdtLattice lattice, double couponPerStep, Double callPrice,
                                  int firstCallStep, double targetClean) {
        double lo = -MAX_SPREAD;
        double hi = MAX_SPREAD;
        double pLo = price(lattice, couponPerStep, callPrice, firstCallStep, lo);
        double pHi = price(lattice, couponPerStep, callPrice, firstCallStep, hi);
        // price is decreasing in spread: pLo is the highest reachable price, pHi the lowest.
        if (targetClean > pLo || targetClean < pHi) {
            return Double.NaN;
        }
        for (int it = 0; it < 200; it++) {
            double mid = 0.5 * (lo + hi);
            if (price(lattice, couponPerStep, callPrice, firstCallStep, mid) > targetClean) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return 0.5 * (lo + hi);
    }
}
