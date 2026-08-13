package io.muniworld.curve;

/**
 * The book-level analytics on top of the lattice (ADR-0018): straight vs callable value, the option value
 * between them, OAS-constant effective duration/convexity from curve bumps, and Kalotay's refunding
 * efficiency. One entry point used by BOTH the per-bond OAS service and the model workbench, so a number
 * on a bond's page and the same number in the workbench can never disagree.
 *
 * <p>Definitions (each rendered with its number in the UI):
 * <ul>
 *   <li><b>Option value</b> = straight value − callable value: what the issuer's call is worth, in points.
 *       Both legs priced on the SAME lattice at the SAME spread.</li>
 *   <li><b>Effective duration/convexity</b>: reprice the callable at the same spread on curves bumped
 *       ±Δ (parallel, continuous): {@code dur = (P₋ − P₊) / (2·P₀·Δ)},
 *       {@code conv = (P₋ + P₊ − 2·P₀) / (P₀·Δ²)}. CONVENTION: Δ = 25bp. The lattice is RECALIBRATED per
 *       bump — that is what "OAS-constant" means, and it is what makes callable duration shorten and
 *       convexity go negative as the call comes into the money (the book's central picture).</li>
 *   <li><b>Refunding efficiency</b> (Kalotay): PV savings of calling NOW ÷ option value being given up =
 *       {@code (straight − callPrice) / (straight − callable)}. Only defined when the bond is currently
 *       callable; his rule of thumb is to refund above ~90%. Reported raw — negative means calling now
 *       destroys value; near 100% means the remaining time-value of the option is exhausted.</li>
 * </ul>
 *
 * <p>Pure floating point per the ADR-0017 §4 boundary; callers round once at a declared scale.
 */
public final class ModelAnalytics {

    /** CONVENTION: the parallel bump for effective duration/convexity — 25bp, the common market choice. */
    public static final double BUMP = 0.0025;

    /** Below this option value (in points) refunding efficiency is a 0/0 and is reported absent. */
    private static final double MIN_OPTION_VALUE = 1e-4;

    private ModelAnalytics() {
    }

    /**
     * Everything computed at one spread.
     *
     * @param straight            value of the same cash flows with the call stripped
     * @param callable            value with the call priced in (equals straight when no call)
     * @param optionValue         straight − callable, ≥ 0
     * @param effDuration         OAS-constant effective duration of the callable, years
     * @param effConvexity        OAS-constant effective convexity, years²
     * @param refundingEfficiency Kalotay efficiency, or null (not currently callable / option value ~ 0)
     */
    public record Result(double straight, double callable, double optionValue,
                         double effDuration, double effConvexity, Double refundingEfficiency) {
    }

    /**
     * Run the full set at a given spread.
     *
     * @param stepDf            base-curve discount factors P(0,(i+1)·dt)
     * @param sigma             lognormal short-rate vol
     * @param dt                step, years
     * @param couponPerStep     per-100 coupon per step
     * @param callPrice         strike per 100, null = non-callable
     * @param firstCallStep     first callable step (1-based), ignored when callPrice is null
     * @param currentlyCallable true when the call date is not in the future as-of the pricing date —
     *                          the precondition for refunding efficiency to mean anything
     * @param spread            the spread (e.g. the solved OAS) both legs are priced at
     */
    public static Result analyze(double[] stepDf, double sigma, double dt, double couponPerStep,
                                 Double callPrice, int firstCallStep, boolean currentlyCallable,
                                 double spread) {
        BdtLattice base = BdtLattice.calibrate(stepDf, sigma, dt);
        double straight = LatticeBondPricer.price(base, couponPerStep, null, 0, spread);
        double callable = callPrice == null ? straight
                : LatticeBondPricer.price(base, couponPerStep, callPrice, firstCallStep, spread);
        double optionValue = straight - callable;

        double p0 = callable;
        double pUp = bumpedPrice(stepDf, sigma, dt, couponPerStep, callPrice, firstCallStep, spread, +BUMP);
        double pDown = bumpedPrice(stepDf, sigma, dt, couponPerStep, callPrice, firstCallStep, spread, -BUMP);
        double effDuration = (pDown - pUp) / (2 * p0 * BUMP);
        double effConvexity = (pDown + pUp - 2 * p0) / (p0 * BUMP * BUMP);

        Double efficiency = null;
        if (callPrice != null && currentlyCallable && optionValue > MIN_OPTION_VALUE) {
            efficiency = (straight - callPrice) / optionValue;
        }
        return new Result(straight, callable, optionValue, effDuration, effConvexity, efficiency);
    }

    /** Reprice on a parallel-bumped curve: zero rates +δ means df·e^(−δ·t); lattice recalibrated. */
    private static double bumpedPrice(double[] stepDf, double sigma, double dt, double couponPerStep,
                                      Double callPrice, int firstCallStep, double spread, double delta) {
        double[] bumped = new double[stepDf.length];
        for (int i = 0; i < stepDf.length; i++) {
            bumped[i] = stepDf[i] * Math.exp(-delta * (i + 1) * dt);
        }
        BdtLattice lattice = BdtLattice.calibrate(bumped, sigma, dt);
        return LatticeBondPricer.price(lattice, couponPerStep, callPrice, firstCallStep, spread);
    }
}
