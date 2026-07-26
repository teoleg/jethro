package io.jethro.app.signal;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Exact-value tests for the ADR-0055 phase-1 signal scoring: directional return, WIN/LOSS/FLAT, and
 *  the rolling hit-rate (FLATs are no bet and don't count against it). */
class SignalScoringTest {

    private static final double FLAT_BPS = 10; // 0.10% noise floor

    @Test
    void directionalReturnFollowsTheCall() {
        // Long 100 → 110 = +10%. Short the same move = −10%. Exact.
        assertEquals(0.10, SignalScoring.directionalReturn(1, bd(100), bd(110)), 1e-12);
        assertEquals(-0.10, SignalScoring.directionalReturn(-1, bd(100), bd(110)), 1e-12);
        assertEquals(0.0, SignalScoring.directionalReturn(0, bd(100), bd(110)), 1e-12);
        assertEquals(0.0, SignalScoring.directionalReturn(1, bd(0), bd(110)), 1e-12, "non-positive entry → 0");
    }

    @Test
    void outcomeThresholdsAtTheNoiseFloor() {
        assertEquals(SignalScoring.Outcome.WIN, SignalScoring.outcome(0.0011, FLAT_BPS));  // +11 bps > 10
        assertEquals(SignalScoring.Outcome.LOSS, SignalScoring.outcome(-0.0011, FLAT_BPS)); // −11 bps
        assertEquals(SignalScoring.Outcome.FLAT, SignalScoring.outcome(0.0005, FLAT_BPS));  // +5 bps < 10
    }

    @Test
    void hitRateExcludesFlatsAndAveragesReturns() {
        // Returns: +2%, +2%, −2%, +0.05% (flat). 2 wins, 1 loss, 1 flat.
        List<Double> rets = List.of(0.02, 0.02, -0.02, 0.0005);
        SignalScoring.Stats s = SignalScoring.aggregate("momentum", rets, FLAT_BPS, 3);
        assertEquals(4, s.resolved());
        assertEquals(2, s.wins());
        assertEquals(1, s.losses());
        assertEquals(1, s.flats());
        assertEquals(3, s.open());
        // hit-rate = wins / (wins+losses) = 2/3, FLAT excluded.
        assertEquals(2.0 / 3.0, s.hitRate(), 1e-12);
        // average return over ALL resolved = (0.02+0.02−0.02+0.0005)/4 = 0.005125 → 51.25 bps.
        // (This expectation read 101.25 and had been failing: the sum is 0.0205, not 0.0405.)
        assertEquals(51.25, s.avgReturnBps(), 1e-9);
        // Sample sd (ADR-0064): deviations from 0.005125 are +0.014875, +0.014875, −0.025125, −0.004625;
        // Σd² = 0.0010951875; /(n−1)=3 → 3.650625e−4; √ = 0.0191066088… → 191.0661 bps.
        assertEquals(191.06608804, s.stdReturnBps(), 1e-8);
        // Standard error = sd/√4 = 95.533 bps — the mean sits well inside one, i.e. it is not evidence.
        assertEquals(95.53304402, s.stdErrorBps(), 1e-8);
    }

    @Test
    void emptyIsZeroedNotNaN() {
        SignalScoring.Stats s = SignalScoring.aggregate("social", List.of(), FLAT_BPS, 0);
        assertEquals(0, s.resolved());
        assertEquals(0.0, s.hitRate(), 1e-12);
        assertEquals(0.0, s.avgReturnBps(), 1e-12);
        assertEquals(0.0, s.stdReturnBps(), 1e-12);
        assertEquals(0.0, s.stdErrorBps(), 1e-12);
    }

    @Test
    void aSingleObservationHasNoDispersion() {
        // One sample cannot estimate a dispersion — it must read zero, never NaN or a divide-by-zero.
        SignalScoring.Stats s = SignalScoring.aggregate("learned", List.of(0.02), FLAT_BPS, 0);
        assertEquals(200.0, s.avgReturnBps(), 1e-9);
        assertEquals(0.0, s.stdReturnBps(), 1e-12);
        assertEquals(0.0, s.stdErrorBps(), 1e-12);
    }

    // ---- ADR-0077: the standard error is taken across emission cohorts, not across observations ----

    private static final long COHORT_WINDOW_MS = 60_000;

    private static SignalScoring.Observation obs(long atMillis, double ret) {
        return new SignalScoring.Observation(atMillis, ret);
    }

    @Test
    void standardErrorIsTakenAcrossCohortsNotObservations() {
        // Two bursts an hour apart, two names each. Worked by hand:
        //   cohort A (t=0)      : +10 bps, +30 bps → mean +20 bps
        //   cohort B (t=1h)     : − 4 bps, +12 bps → mean + 4 bps
        //   Fama–MacBeth mean   = (20 + 4)/2                     = +12 bps
        //   sd of cohort means  = |20 − 4|/√2                    = 11.3137085 bps
        //   standard error      = 11.3137085/√2                  =  8.0 bps  ← exactly
        List<SignalScoring.Observation> o = List.of(
                obs(0, 0.0010), obs(100, 0.0030),
                obs(3_600_000, -0.0004), obs(3_600_100, 0.0012));
        SignalScoring.Stats s = SignalScoring.aggregate("reversion", o, COHORT_WINDOW_MS, FLAT_BPS, 0);

        assertEquals(4, s.resolved(), "every observation still counts as volume");
        assertEquals(2, s.cohorts(), "…but they came from two independent draws");
        assertEquals(12.0, s.avgReturnBps(), 1e-9);
        assertEquals(11.31370850, s.stdCohortMeanBps(), 1e-8);
        assertEquals(8.0, s.stdErrorBps(), 1e-9);
        // The per-observation dispersion is unchanged and still reported — it is just no longer what the
        // standard error divides. Treating these 4 as i.i.d. would have given 13.9522997/√4 = 6.9761 bps,
        // i.e. a standard error 15% too small and a t-statistic 15% too large.
        assertEquals(13.95229969, s.stdReturnBps(), 1e-8);
        assertEquals(2, s.wins());   // +30 and +12 bps clear the floor
        assertEquals(2, s.flats());  // +10 (not >10) and −4 do not
    }

    @Test
    void oneCrossSectionIsNoEvidenceHoweverWideItIs() {
        // The live `reversion` shape: a whole 23-name cross-section emitted in one ~300ms burst. One
        // draw of the market supports no standard error at all — σ/√23 would have manufactured one.
        List<SignalScoring.Observation> o = new java.util.ArrayList<>();
        for (int i = 0; i < 23; i++) {
            o.add(obs(i * 13L, 0.0004 + i * 0.00001));
        }
        SignalScoring.Stats s = SignalScoring.aggregate("reversion", o, COHORT_WINDOW_MS, FLAT_BPS, 0);
        assertEquals(23, s.resolved());
        assertEquals(1, s.cohorts());
        assertEquals(0.0, s.stdErrorBps(), 1e-12, "a single cohort yields no standard error");
        assertEquals(0.0, s.stdCohortMeanBps(), 1e-12);
    }

    @Test
    void aStaggeredEmitterIsUnaffected() {
        // A source that calls one name at a time already has one observation per cohort, so the estimator
        // reduces exactly to the ordinary i.i.d. one — no special case, no discontinuity at the boundary.
        List<SignalScoring.Observation> o = List.of(
                obs(0, 0.02), obs(3_600_000, 0.02), obs(7_200_000, -0.02), obs(10_800_000, 0.0005));
        SignalScoring.Stats s = SignalScoring.aggregate("momentum", o, COHORT_WINDOW_MS, FLAT_BPS, 3);
        SignalScoring.Stats iid = SignalScoring.aggregate("momentum", List.of(0.02, 0.02, -0.02, 0.0005),
                FLAT_BPS, 3);
        assertEquals(4, s.cohorts());
        assertEquals(iid.avgReturnBps(), s.avgReturnBps(), 1e-12);
        assertEquals(iid.stdReturnBps(), s.stdReturnBps(), 1e-12);
        assertEquals(iid.stdErrorBps(), s.stdErrorBps(), 1e-12);
        assertEquals(95.53304402, s.stdErrorBps(), 1e-8);
    }

    @Test
    void theLiveTrendReadingLosesItsSpuriousSignificance() {
        // The five hourly `trend` cross-sections measured on 2026-07-26 (23 names each), as their cohort
        // mean directional returns. Pooled as 115 i.i.d. observations the desk read sd 27.7797/√115 =
        // 2.5905 bps ⇒ t = −5.25. Across the five draws that actually varied:
        //   mean   = (−19.9386 −31.6952 −0.3047 −11.8945 −2.7085)/5 = −13.3083 bps
        //   sd     = √(665.48719/4)                                 =  12.8985 bps
        //   stdErr = 12.8985/√5                                     =   5.7684 bps  ⇒ t = −2.31
        // The i.i.d. standard error was 2.23× too small.
        double[] cohortMeansBps = {-19.9386, -31.6952, -0.3047, -11.8945, -2.7085};
        List<SignalScoring.Observation> o = new java.util.ArrayList<>();
        for (int i = 0; i < cohortMeansBps.length; i++) {
            o.add(obs(i * 3_600_000L, cohortMeansBps[i] / 1e4));
        }
        SignalScoring.Stats s = SignalScoring.aggregate("trend", o, COHORT_WINDOW_MS, FLAT_BPS, 0);
        assertEquals(5, s.cohorts());
        assertEquals(-13.30830, s.avgReturnBps(), 1e-5);
        assertEquals(12.89852, s.stdCohortMeanBps(), 1e-5);
        assertEquals(5.76840, s.stdErrorBps(), 1e-5);
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v);
    }
}
