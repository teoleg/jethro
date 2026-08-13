package io.muniworld.curve;

/**
 * A Black-Derman-Toy short-rate lattice (ADR-0018): recombining binomial, lognormal node rates
 * {@code r(i,j) = a_i · exp(σ√Δt · (2j − i))}, risk-neutral probability ½/½, constant σ — the model family
 * Kalotay co-authored, driven by the LOGNORMAL σ that ADR-0017 measures from the Fed's own curve history.
 *
 * <p><b>Calibration is exact by construction.</b> Forward induction with Arrow-Debreu state prices: at each
 * step the level {@code a_i} is solved (bisection) so the lattice reprices the benchmark discount factor
 * {@code P(0, t_{i+1})} exactly. So the lattice reproduces the input zero curve to solver tolerance BEFORE
 * any bond is priced on it — mispricing a zero would silently poison every OAS downstream. At σ = 0 the
 * construction degenerates to the curve's exact forward rates (tested identity).
 *
 * <p>All rates continuously compounded, as the GSW curve is published (no compounding conversion to get
 * wrong). Everything here is transcendental floating-point mathematics per the ADR-0017 §4 exactness
 * boundary — no money amounts live in this class; callers round results once at a declared scale.
 */
public final class BdtLattice {

    private final double[][] rates;   // rates[i][j], j = 0..i — the short rate over [t_i, t_i+dt)
    private final double dt;

    private BdtLattice(double[][] rates, double dt) {
        this.rates = rates;
        this.dt = dt;
    }

    /**
     * Calibrate an n-step lattice to a zero curve.
     *
     * @param stepDf discount factors {@code P(0, (i+1)·dt)} for i = 0..n-1 — from
     *               {@link NelsonSiegelSvensson#discountFactor}; must be positive and non-increasing
     * @param sigma  annualised lognormal short-rate vol (0.15 = 15%), σ ≥ 0
     * @param dt     step length in years
     */
    public static BdtLattice calibrate(double[] stepDf, double sigma, double dt) {
        int n = stepDf.length;
        if (n == 0 || dt <= 0 || sigma < 0) {
            throw new IllegalArgumentException("need steps, dt > 0 and sigma >= 0");
        }
        double last = 1.0;
        for (double df : stepDf) {
            if (df <= 0 || df > last) {
                throw new IllegalArgumentException("discount factors must be positive and non-increasing");
            }
            last = df;
        }
        double[][] rates = new double[n][];
        double[] q = {1.0};                       // Arrow-Debreu prices at step 0: the sure dollar
        for (int i = 0; i < n; i++) {
            final int step = i;
            final double[] ad = q;
            // f(a) = sum_j Q(i,j)·exp(−a·m_j·dt) − P(0,t_{i+1}) is strictly decreasing in a: bisect.
            double lo = 1e-9;
            double hi = 5.0;                      // a 500% short rate bounds any real curve
            for (int it = 0; it < 200; it++) {
                double mid = 0.5 * (lo + hi);
                if (impliedZeroPrice(ad, mid, sigma, step, dt) > stepDf[i]) {
                    lo = mid;                     // lattice discounts too little — rate up
                } else {
                    hi = mid;
                }
            }
            double a = 0.5 * (lo + hi);
            // Kalotay's repricing discipline, enforced not assumed: if the bisection could not actually
            // reach the target (a curve step implying a negative/zero forward — a lognormal model cannot
            // produce it — or a corrupt DF), REFUSE. Silently carrying a mispriced step would contaminate
            // every bond priced on the lattice, which is exactly what curve validation exists to prevent.
            double residual = impliedZeroPrice(q, a, sigma, step, dt) - stepDf[i];
            if (Math.abs(residual) > 1e-9) {
                throw new IllegalStateException("lattice cannot reprice the curve at step " + (i + 1)
                        + " (target DF " + stepDf[i] + ", residual " + residual
                        + ") — the curve implies a forward a lognormal short-rate model cannot fit");
            }
            rates[i] = new double[i + 1];
            for (int j = 0; j <= i; j++) {
                rates[i][j] = a * Math.exp(sigma * Math.sqrt(dt) * (2.0 * j - i));
            }
            // Roll the Arrow-Debreu prices one step forward under ½/½.
            double[] next = new double[i + 2];
            for (int j = 0; j <= i; j++) {
                double df = Math.exp(-rates[i][j] * dt);
                next[j] += 0.5 * q[j] * df;
                next[j + 1] += 0.5 * q[j] * df;
            }
            q = next;
        }
        return new BdtLattice(rates, dt);
    }

    /** The zero price the lattice implies for maturity t_{step+1} at trial level {@code a}. */
    private static double impliedZeroPrice(double[] ad, double a, double sigma, int step, double dt) {
        double sum = 0;
        for (int j = 0; j <= step; j++) {
            sum += ad[j] * Math.exp(-a * Math.exp(sigma * Math.sqrt(dt) * (2.0 * j - step)) * dt);
        }
        return sum;
    }

    public int steps() {
        return rates.length;
    }

    public double dt() {
        return dt;
    }

    /** The short rate at node (i, j) — j up-moves after i steps. */
    public double rate(int i, int j) {
        return rates[i][j];
    }

    /** The discount factor the lattice implies for maturity t_n — for calibration round-trip checks. */
    public double impliedDiscount(int nSteps) {
        double[] q = {1.0};
        for (int i = 0; i < nSteps; i++) {
            double[] next = new double[i + 2];
            for (int j = 0; j <= i; j++) {
                double df = Math.exp(-rates[i][j] * dt);
                next[j] += 0.5 * q[j] * df;
                next[j + 1] += 0.5 * q[j] * df;
            }
            q = next;
        }
        double sum = 0;
        for (double v : q) {
            sum += v;
        }
        return sum;
    }
}
