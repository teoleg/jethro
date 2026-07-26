package io.jethro.app.fusion;

/**
 * Tail probabilities for the desk's evidence tests — pure, dimensionless, exactly testable against
 * published statistical tables.
 *
 * <p><b>Why this exists (ADR-0081).</b> A t-statistic only means "so many standard errors" if the
 * standard error is known. When it is <em>estimated</em> from B independent draws, the ratio follows
 * Student's t with {@code B − 1} degrees of freedom, not a normal — and the two differ enormously in
 * the small-B regime this desk actually lives in. The Fama–MacBeth standard error introduced by
 * ADR-0077 is estimated across emission cohorts, of which a cross-sectional source produces one per
 * measurement horizon, so B is routinely single-digit. At B = 3 the true two-sided 95% critical value
 * is 4.303 (Student, "The Probable Error of a Mean", <i>Biometrika</i> 1908); a fixed hurdle of 2.0
 * there is roughly an 82% test wearing a 95% label. This class supplies the correct reference
 * distribution so the hurdle delivers the confidence it claims at every sample size, and converges to
 * the normal — exactly, in the limit — once the sample is large enough for the normal to be right.
 *
 * <p>Nothing here is a money, risk or exposure number (ADR-0016 / invariant 7): in come a statistic
 * and a degrees-of-freedom count, out comes a probability in [0,1]. {@code double} is the correct
 * representation — these are dimensionless probabilities, never a price, quantity or PnL.
 */
public final class Significance {

    private Significance() {
    }

    /**
     * One-sided tail probability {@code P(T > t)} for Student's t with {@code df} degrees of freedom,
     * via the regularized incomplete beta identity
     * <pre>
     *   P(T &gt; t) = ½ · I_x(df/2, ½),   x = df / (df + t²),   for t ≥ 0
     * </pre>
     * and the symmetry {@code P(T > −t) = 1 − P(T > t)} below zero.
     *
     * <p>Worked check (encoded as a test): {@code studentTUpperTail(4.30265, 2) = 0.02500} and
     * {@code studentTUpperTail(2.04227, 30) = 0.02500} — the published 97.5% points at 2 and 30
     * degrees of freedom.
     *
     * @return 1.0 (i.e. no evidence at all) when {@code df < 1} or the statistic is not a number —
     *         a caller comparing against a small α then never clears, which is the safe direction.
     */
    public static double studentTUpperTail(double t, double df) {
        if (Double.isNaN(t) || Double.isNaN(df) || df < 1.0) {
            return 1.0;
        }
        if (t == Double.POSITIVE_INFINITY) {
            return 0.0;
        }
        if (t == Double.NEGATIVE_INFINITY) {
            return 1.0;
        }
        double x = df / (df + t * t);
        double half = 0.5 * regularizedIncompleteBeta(x, 0.5 * df, 0.5);
        return t >= 0 ? half : 1.0 - half;
    }

    /**
     * The one-sided tail probability a hurdle of {@code z} standard errors asserts under the normal —
     * i.e. the confidence level a fixed t-hurdle dial was always claiming. Reuses the single Φ
     * implementation in {@link TelemetryWeights} so the desk has exactly one normal CDF.
     */
    public static double normalUpperTail(double z) {
        return 1.0 - TelemetryWeights.standardNormalCdf(z);
    }

    /**
     * The regularized incomplete beta function {@code I_x(a, b)}, by the modified Lentz evaluation of
     * its continued fraction with the standard {@code x > (a+1)/(a+b+2)} reflection to the rapidly
     * converging branch (Press et al., <i>Numerical Recipes</i> §6.4).
     */
    static double regularizedIncompleteBeta(double x, double a, double b) {
        if (x <= 0.0) {
            return 0.0;
        }
        if (x >= 1.0) {
            return 1.0;
        }
        // exp of the log form: the beta density's normalising factor times x^a (1−x)^b, which
        // underflows catastrophically if formed directly at the sample sizes this desk reaches.
        double front = Math.exp(logGamma(a + b) - logGamma(a) - logGamma(b)
                + a * Math.log(x) + b * Math.log1p(-x));
        if (x < (a + 1.0) / (a + b + 2.0)) {
            return front * betaContinuedFraction(x, a, b) / a;
        }
        return 1.0 - front * betaContinuedFraction(1.0 - x, b, a) / b;
    }

    /** Lentz's algorithm for the continued fraction of the incomplete beta; converges for x below the switch point. */
    private static double betaContinuedFraction(double x, double a, double b) {
        final double tiny = 1e-300;
        double qab = a + b;
        double qap = a + 1.0;
        double qam = a - 1.0;
        double c = 1.0;
        double d = 1.0 - qab * x / qap;
        if (Math.abs(d) < tiny) {
            d = tiny;
        }
        d = 1.0 / d;
        double h = d;
        for (int m = 1; m <= 300; m++) {
            int m2 = 2 * m;
            double even = m * (b - m) * x / ((qam + m2) * (a + m2));
            d = 1.0 + even * d;
            if (Math.abs(d) < tiny) {
                d = tiny;
            }
            c = 1.0 + even / c;
            if (Math.abs(c) < tiny) {
                c = tiny;
            }
            d = 1.0 / d;
            h *= d * c;
            double odd = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2));
            d = 1.0 + odd * d;
            if (Math.abs(d) < tiny) {
                d = tiny;
            }
            c = 1.0 + odd / c;
            if (Math.abs(c) < tiny) {
                c = tiny;
            }
            d = 1.0 / d;
            double delta = d * c;
            h *= delta;
            if (Math.abs(delta - 1.0) < 3e-15) {
                break;
            }
        }
        return h;
    }

    // Lanczos g = 7, n = 9 — |relative error| < 1e-13 over the half-plane this class evaluates.
    private static final double[] LANCZOS = {
            0.99999999999980993, 676.5203681218851, -1259.1392167224028,
            771.32342877765313, -176.61502916214059, 12.507343278686905,
            -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7};

    /** log Γ(z) by the Lanczos approximation, with the reflection formula below ½. */
    static double logGamma(double z) {
        if (z < 0.5) {
            return Math.log(Math.PI / Math.abs(Math.sin(Math.PI * z))) - logGamma(1.0 - z);
        }
        double zz = z - 1.0;
        double series = LANCZOS[0];
        for (int i = 1; i < LANCZOS.length; i++) {
            series += LANCZOS[i] / (zz + i);
        }
        double t = zz + LANCZOS.length - 1.5; // = zz + g + ½ with g = 7
        return 0.5 * Math.log(2.0 * Math.PI) + (zz + 0.5) * Math.log(t) - t + Math.log(series);
    }
}
