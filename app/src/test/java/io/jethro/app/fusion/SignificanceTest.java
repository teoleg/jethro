package io.jethro.app.fusion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0081. Every case checks the implementation against a value that exists independently of it —
 * a published Student-t table point, a closed form, or an analytic identity — because a tail
 * probability that gates whether the desk may put on risk must be verifiable without trusting the code
 * that produced it.
 */
class SignificanceTest {

    /**
     * The published 97.5% points of Student's t. These are the numbers in the back of every statistics
     * text (Student, <i>Biometrika</i> 1908; Fisher 1925), and they are the whole reason this class
     * exists: at 2 degrees of freedom the critical value is 4.303, more than twice the fixed 2.0 the
     * edge gate used to apply there.
     */
    @Test
    void matchesThePublishedTwoAndAHalfPercentPointsOfStudentsT() {
        assertEquals(0.025, Significance.studentTUpperTail(12.7062, 1), 1e-5);
        assertEquals(0.025, Significance.studentTUpperTail(4.30265, 2), 1e-5);
        assertEquals(0.025, Significance.studentTUpperTail(3.18245, 3), 1e-5);
        assertEquals(0.025, Significance.studentTUpperTail(2.77645, 4), 1e-5);
        assertEquals(0.025, Significance.studentTUpperTail(2.22814, 10), 1e-5);
        assertEquals(0.025, Significance.studentTUpperTail(2.04227, 30), 1e-5);
        assertEquals(0.025, Significance.studentTUpperTail(1.98422, 99), 1e-5);
    }

    /** The 5% points, as a second independent row of the same table. */
    @Test
    void matchesThePublishedFivePercentPointsOfStudentsT() {
        assertEquals(0.05, Significance.studentTUpperTail(6.31375, 1), 1e-5);
        assertEquals(0.05, Significance.studentTUpperTail(2.91999, 2), 1e-5);
        assertEquals(0.05, Significance.studentTUpperTail(1.81246, 10), 1e-5);
        assertEquals(0.05, Significance.studentTUpperTail(1.69726, 30), 1e-5);
    }

    /** t is symmetric about zero, so the median is exactly ½ at every df. */
    @Test
    void isSymmetricAboutZero() {
        for (double df : new double[]{1, 2, 3, 7, 29, 250}) {
            assertEquals(0.5, Significance.studentTUpperTail(0.0, df), 1e-12);
            assertEquals(1.0, Significance.studentTUpperTail(1.7, df)
                    + Significance.studentTUpperTail(-1.7, df), 1e-12);
        }
    }

    /**
     * The df = 1 case has a closed form — Student's t on one degree of freedom IS the standard Cauchy,
     * whose upper tail is ½ − atan(t)/π. Checked across the range, it pins the continued fraction to an
     * elementary function with no table in between.
     */
    @Test
    void agreesWithTheCauchyClosedFormAtOneDegreeOfFreedom() {
        for (double t : new double[]{0.1, 0.5, 1.0, 2.0, 5.0, 20.0, 100.0}) {
            double cauchy = 0.5 - Math.atan(t) / Math.PI;
            assertEquals(cauchy, Significance.studentTUpperTail(t, 1), 1e-12);
        }
    }

    /**
     * The df = 2 case also has a closed form: P(T &gt; t) = ½·(1 − t/√(2+t²)).
     * This is the degrees of freedom the live desk actually sits at — three resolved cohorts — so it is
     * the one worth pinning hardest.
     */
    @Test
    void agreesWithTheClosedFormAtTwoDegreesOfFreedom() {
        for (double t : new double[]{0.0, 0.25, 1.0, 1.968, 4.30265, 30.0}) {
            double exact = 0.5 * (1.0 - t / Math.sqrt(2.0 + t * t));
            assertEquals(exact, Significance.studentTUpperTail(t, 2), 1e-12);
        }
    }

    /** As the sample grows the reference distribution becomes the normal — the limit the old gate assumed. */
    @Test
    void convergesToTheNormalAtLargeDegreesOfFreedom() {
        double normal = Significance.normalUpperTail(2.0); // α the t-hurdle dial 2.0 asserts
        assertEquals(0.02275, normal, 1e-5);
        assertEquals(normal, Significance.studentTUpperTail(2.0, 1_000_000), 1e-6);
        // ...and is strictly more demanding at every finite sample size on the way there.
        assertTrue(Significance.studentTUpperTail(2.0, 2) > normal);
        assertTrue(Significance.studentTUpperTail(2.0, 30) > normal);
        assertTrue(Significance.studentTUpperTail(2.0, 30) < Significance.studentTUpperTail(2.0, 2));
    }

    /** The tail is monotone in the statistic and in the sample — sanity the table points cannot give alone. */
    @Test
    void isMonotoneInBothArguments() {
        double previous = 1.0;
        for (double t = -4.0; t <= 4.0; t += 0.25) {
            double p = Significance.studentTUpperTail(t, 5);
            assertTrue(p < previous, "tail must fall as the statistic rises");
            previous = p;
        }
        double last = Significance.studentTUpperTail(2.0, 1);
        for (double df = 2; df <= 200; df += 1) {
            double p = Significance.studentTUpperTail(2.0, df);
            assertTrue(p <= last, "tail must fall as evidence accumulates");
            last = p;
        }
    }

    /** No sample, no evidence: anything the caller cannot form a statistic from reads as p = 1. */
    @Test
    void degenerateInputsReadAsNoEvidence() {
        assertEquals(1.0, Significance.studentTUpperTail(50.0, 0), 1e-12);
        assertEquals(1.0, Significance.studentTUpperTail(50.0, 0.5), 1e-12);
        assertEquals(1.0, Significance.studentTUpperTail(Double.NaN, 30), 1e-12);
        assertEquals(1.0, Significance.studentTUpperTail(50.0, Double.NaN), 1e-12);
        assertEquals(0.0, Significance.studentTUpperTail(Double.POSITIVE_INFINITY, 30), 1e-12);
        assertEquals(1.0, Significance.studentTUpperTail(Double.NEGATIVE_INFINITY, 30), 1e-12);
    }

    /** log Γ against exact factorials — the only place this class could silently drift. */
    @Test
    void logGammaMatchesTheFactorials() {
        assertEquals(0.0, Significance.logGamma(1.0), 1e-12);          // 0! = 1
        assertEquals(0.0, Significance.logGamma(2.0), 1e-12);          // 1! = 1
        assertEquals(Math.log(6.0), Significance.logGamma(4.0), 1e-12);   // 3! = 6
        assertEquals(Math.log(3628800.0), Significance.logGamma(11.0), 1e-10); // 10!
        assertEquals(0.5 * Math.log(Math.PI), Significance.logGamma(0.5), 1e-12); // Γ(½) = √π
    }

    /** The regularized incomplete beta at its boundaries and at the symmetric midpoint. */
    @Test
    void incompleteBetaHasItsAnalyticValues() {
        assertEquals(0.0, Significance.regularizedIncompleteBeta(0.0, 2.0, 3.0), 1e-12);
        assertEquals(1.0, Significance.regularizedIncompleteBeta(1.0, 2.0, 3.0), 1e-12);
        assertEquals(0.5, Significance.regularizedIncompleteBeta(0.5, 3.0, 3.0), 1e-12);
        // I_x(1, b) = 1 − (1−x)^b, elementary.
        assertEquals(1.0 - Math.pow(0.75, 2.5),
                Significance.regularizedIncompleteBeta(0.25, 1.0, 2.5), 1e-12);
    }
}
