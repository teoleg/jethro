package io.muniworld.curve;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exact-value tests for the ADR-0017 curve and volatility mathematics. Every assertion is a number computed
 * by hand from the formula in the Javadoc, not a value captured from a previous run — a regression test that
 * only pins current behaviour cannot tell you the behaviour was ever right.
 */
class CurveMathTest {

    // ---------------------------------------------------------------- Nelson-Siegel-Svensson

    /**
     * Worked example 1. B0 = 4.0, B1 = −1.0, B2 = B3 = 0, T1 = T2 = 1, n = 1.
     * n/T1 = 1, so the slope loading is (1 − e^(−1))/1 = 1 − 0.36787944117144233 = 0.6321205588285577.
     * y(1) = 4.0 + (−1.0)(0.6321205588285577) = 3.3678794411714423 % → 0.03367879 as a rate at 8dp.
     */
    @Test
    void zeroRateMatchesTheHandComputedSvenssonValue() {
        NelsonSiegelSvensson c = new NelsonSiegelSvensson(4.0, -1.0, 0, 0, 1, 1);
        assertEquals(new BigDecimal("0.03367879"), c.zeroRate(1));
    }

    /**
     * Worked example 2, exercising the hump term. B0 = 5, B1 = −2, B2 = 3, B3 = 0, T1 = 2, T2 = 1, n = 2.
     * n/T1 = 1 → level loading 0.6321205588285577, hump loading 0.6321205588285577 − 0.36787944117144233
     * = 0.2642411176571154.
     * y(2) = 5 + (−2)(0.6321205588285577) + 3(0.2642411176571154)
     *      = 5 − 1.2642411176571154 + 0.7927233529713462 = 4.528482235314231 % → 0.04528482 at 8dp.
     */
    @Test
    void humpTermIsAppliedWithItsOwnDecay() {
        NelsonSiegelSvensson c = new NelsonSiegelSvensson(5.0, -2.0, 3.0, 0, 2, 1);
        assertEquals(new BigDecimal("0.04528482"), c.zeroRate(2));
    }

    /** y(0) = B0 + B1 is the instantaneous short rate — the lattice's first node. No division by zero. */
    @Test
    void shortRateIsTheLimitAtZeroMaturity() {
        NelsonSiegelSvensson c = new NelsonSiegelSvensson(4.5, -1.5, 2.0, 1.0, 1.5, 8.0);
        assertEquals(new BigDecimal("0.03000000"), c.shortRate());
        assertEquals(c.shortRate(), c.zeroRate(0));
    }

    /** A blank fourth term in the Fed's early history IS plain Nelson-Siegel — same curve, not an estimate. */
    @Test
    void zeroBeta3ReproducesNelsonSiegelExactly() {
        NelsonSiegelSvensson nss = new NelsonSiegelSvensson(4.0, -1.0, 2.0, 0.0, 1.5, 7.0);
        NelsonSiegelSvensson other = new NelsonSiegelSvensson(4.0, -1.0, 2.0, 0.0, 1.5, 99.0);
        for (double n : new double[] {0.5, 1, 5, 10, 30}) {
            assertEquals(nss.zeroRate(n), other.zeroRate(n), "tau2 must not matter when beta3 is 0 (n=" + n + ")");
        }
    }

    /** DF = e^(−y·n): a flat 5% curve for 10 years discounts to e^(−0.5) = 0.606530659713. */
    @Test
    void discountFactorIsContinuouslyCompounded() {
        NelsonSiegelSvensson flat = new NelsonSiegelSvensson(5.0, 0, 0, 0, 1, 1);
        assertEquals(new BigDecimal("0.05000000"), flat.zeroRate(10));
        assertEquals(new BigDecimal("0.606530659713"), flat.discountFactor(10));
        assertEquals(new BigDecimal("1.000000000000"), flat.discountFactor(0));
    }

    @Test
    void degenerateParametersAreRejectedRatherThanSilentlyFudged() {
        assertThrows(IllegalArgumentException.class,
                () -> new NelsonSiegelSvensson(4, -1, 0, 0, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new NelsonSiegelSvensson(4, -1, 0, 2.0, 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new NelsonSiegelSvensson(4, -1, 0, 0, 1, 1).zeroRate(-1));
    }

    // ---------------------------------------------------------------- realized volatility

    /**
     * Worked example. r = [100, 110, 100, 110, 100, 100] bp → five differences [+10, −10, +10, −10, 0],
     * mean 0. Squared deviations 100+100+100+100+0 = 400; sample variance 400/(5−1) = 100; daily stdev
     * exactly 10 bp. Annualised: 10 × √252 = 10 × 15.874507866387544 = 158.74507866... → 158.745079 at 6dp.
     */
    @Test
    void normalVolMatchesTheHandComputedAnnualisedStdev() {
        RealizedVol.Estimate e = RealizedVol.normal(List.of(100.0, 110.0, 100.0, 110.0, 100.0, 100.0));
        assertEquals(new BigDecimal("158.745079"), e.sigma());
        assertEquals(5, e.observations());
        assertEquals(0, e.excluded());
    }

    /**
     * Normal vol is in RATE units, so doubling every rate doubles sigma.
     *
     * <p>Asserted to within one unit at the stored scale, not exactly: sigma is rounded ONCE at 6dp, so
     * {@code round(x)*2} and {@code round(2x)} legitimately differ in the last digit (here 317.490158 vs
     * 317.490157). Demanding exact equality would be asserting that double-rounding is associative, which
     * it is not — and "fixing" the code to satisfy it would mean rounding later, in more places.
     */
    @Test
    void normalVolScalesLinearlyWithTheRateLevel() {
        List<Double> base = List.of(100.0, 110.0, 100.0, 110.0, 100.0, 100.0);
        List<Double> doubled = base.stream().map(r -> r * 2).toList();
        BigDecimal expected = RealizedVol.normal(base).sigma().multiply(new BigDecimal("2"));
        BigDecimal actual = RealizedVol.normal(doubled).sigma();
        assertTrue(expected.subtract(actual).abs().compareTo(new BigDecimal("0.000002")) <= 0,
                "expected ~" + expected + " but got " + actual);
    }

    /** Lognormal vol is scale-FREE — that is the defining property, and the reason BDT uses it. */
    @Test
    void lognormalVolIsInvariantToTheRateLevel() {
        List<Double> base = List.of(100.0, 110.0, 100.0, 110.0, 100.0, 100.0);
        for (double factor : new double[] {2, 10, 0.5}) {
            List<Double> scaled = base.stream().map(r -> r * factor).toList();
            assertEquals(RealizedVol.lognormal(base).sigma(), RealizedVol.lognormal(scaled).sigma(),
                    "lognormal sigma must not move when every rate is scaled by " + factor);
        }
    }

    /**
     * Rates at or below the 1bp floor are EXCLUDED from the lognormal estimate and COUNTED — never silently
     * dropped. Two of these five steps touch a zero rate, so both are excluded and reported.
     */
    @Test
    void lognormalExcludesAndCountsRatesAtTheZeroBound() {
        List<Double> withZeros = List.of(100.0, 110.0, 0.0, 100.0, 110.0, 100.0);
        RealizedVol.Estimate e = RealizedVol.lognormal(withZeros);
        assertEquals(2, e.excluded(), "both differences touching the 0bp observation must be excluded");
        assertEquals(3, e.observations());
        // The normal estimate has no such restriction and keeps every observation.
        assertEquals(5, RealizedVol.normal(withZeros).observations());
    }

    /** Fewer than two differences cannot yield a sample stdev — that is zero observations, not a sigma. */
    @Test
    void tooShortASeriesReportsNoObservationsRatherThanAFabricatedSigma() {
        assertEquals(0, RealizedVol.normal(List.of(100.0)).observations());
        assertEquals(1, RealizedVol.normal(List.of(100.0, 110.0)).observations());
        assertEquals(BigDecimal.ZERO.setScale(6), RealizedVol.normal(List.of(100.0, 110.0)).sigma());
    }

    /** A band shorter than one window is ABSENT, not approximated from a partial window (ADR-0011). */
    @Test
    void bandIsNullWhenHistoryIsShorterThanTheWindow() {
        List<Double> shortSeries = List.of(100.0, 110.0, 100.0, 110.0);
        assertNull(RealizedVol.band(shortSeries, 252, RealizedVol::normal));
        assertNotNull(RealizedVol.band(shortSeries, 3, RealizedVol::normal));
    }

    /** The band is ordered p10 <= p50 <= p90 and counts its windows — the sanity every reader assumes. */
    @Test
    void bandIsOrderedAndCountsItsWindows() {
        List<Double> series = new ArrayList<>();
        double r = 100;
        for (int i = 0; i < 300; i++) {
            // deterministic, widening oscillation → genuinely different vol across windows
            r += ((i % 2 == 0) ? 1 : -1) * (1 + i / 50.0);
            series.add(r);
        }
        RealizedVol.Band band = RealizedVol.band(series, 60, RealizedVol::normal);
        assertNotNull(band);
        assertTrue(band.p10().compareTo(band.p50()) <= 0, "p10 must not exceed p50");
        assertTrue(band.p50().compareTo(band.p90()) <= 0, "p50 must not exceed p90");
        assertTrue(band.p10().compareTo(band.p90()) < 0, "a varying series must produce a non-degenerate band");
        assertEquals(300 - 60 + 1, band.windows());
    }

    /** Nearest-rank percentile: rank = ceil(p/100 × n), clamped — no interpolation variant to guess at. */
    @Test
    void percentileUsesNearestRank() {
        List<BigDecimal> sorted = List.of(
                new BigDecimal("1"), new BigDecimal("2"), new BigDecimal("3"),
                new BigDecimal("4"), new BigDecimal("5"));
        assertEquals(new BigDecimal("1"), RealizedVol.percentile(sorted, 10));   // ceil(0.5) = 1
        assertEquals(new BigDecimal("3"), RealizedVol.percentile(sorted, 50));   // ceil(2.5) = 3
        assertEquals(new BigDecimal("5"), RealizedVol.percentile(sorted, 90));   // ceil(4.5) = 5
        assertEquals(new BigDecimal("5"), RealizedVol.percentile(sorted, 100));
    }
}
