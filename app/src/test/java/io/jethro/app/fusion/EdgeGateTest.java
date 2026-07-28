package io.jethro.app.fusion;

import io.jethro.app.signal.SignalScoring;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0064 cost-aware edge gate. Every case is a worked example: measured expectancy and dispersion in,
 * a t-stat against measured round-trip cost out, and a reduce-only verdict when nothing clears it.
 */
class EdgeGateTest {

    private static final EdgeGate.Params P = new EdgeGate.Params(30, 2.0);

    /** Stats with an explicit mean/dispersion — the only two fields the gate reads. */
    private static SignalScoring.Stats stat(String source, long n, double meanBps, double stdBps) {
        return new SignalScoring.Stats(source, n, 0, 0, 0, 0, 0.0, meanBps, stdBps);
    }

    @Test
    void aLosingSourceIsReduceOnly() {
        // mean −51.26 bps, sd 854.12, n 233 → se = 854.12/√233 = 55.96; net of 10bps cost = −61.26
        // t = −61.26/55.96 = −1.095 → far below +2. No edge, and it is not even close.
        var d = EdgeGate.evaluate(List.of(stat("momentum", 233, -51.26, 854.12)), 10.0, P);
        assertFalse(d.mayIncrease());
        assertEquals(-1.0948, d.sources().get(0).tStat(), 1e-4);
        assertEquals(-61.26, d.sources().get(0).netEdgeBps(), 1e-9);
    }

    @Test
    void aPositiveButInsignificantMeanIsNotEvidence() {
        // +40 bps looks like a winner until the dispersion is read: se = 900/√100 = 90, net = 30,
        // t = 0.333. This is the whole point of the hurdle — a positive window is not an edge.
        var d = EdgeGate.evaluate(List.of(stat("mean-reversion", 100, 40.0, 900.0)), 10.0, P);
        assertFalse(d.mayIncrease());
        assertEquals(0.3333, d.sources().get(0).tStat(), 1e-4);
    }

    @Test
    void aSourceThatBeatsCostWithSignificanceOpensTheGate() {
        // mean +30 bps, sd 60, n 144 → se = 5.0; net of 10bps cost = 20 → t = 4.0 ≥ 2 → open.
        var d = EdgeGate.evaluate(List.of(stat("hypothesis", 144, 30.0, 60.0)), 10.0, P);
        assertTrue(d.mayIncrease());
        assertEquals(4.0, d.sources().get(0).tStat(), 1e-9);
        assertTrue(d.reason().contains("hypothesis"));
    }

    @Test
    void oneQualifyingSourceIsEnoughEvenBesideLosers() {
        var d = EdgeGate.evaluate(List.of(
                stat("momentum", 233, -51.26, 854.12),
                stat("hypothesis", 144, 30.0, 60.0)), 10.0, P);
        assertTrue(d.mayIncrease());
        // sorted by t-stat: the winner leads the evidence list
        assertEquals("hypothesis", d.sources().get(0).source());
    }

    @Test
    void costIsWhatTurnsAThinWinnerIntoALoser() {
        // Same source, same statistics: gross it clears the hurdle, net of a 30bps round trip it does not.
        var s = stat("mean-reversion", 400, 20.0, 100.0); // se = 5.0
        assertTrue(EdgeGate.evaluate(List.of(s), 0.0, P).mayIncrease());   // t = 20/5 = 4.0
        assertFalse(EdgeGate.evaluate(List.of(s), 30.0, P).mayIncrease()); // t = −10/5 = −2.0
    }

    @Test
    void aThinSampleCannotOpenTheGate() {
        // n = 12 (< minSample 30) with a spectacular mean — exactly the lucky-streak case the
        // minimum sample exists to refuse. t would be huge; the sample is not allowed to speak.
        var d = EdgeGate.evaluate(List.of(stat("social", 12, 515.04, 100.0)), 10.0, P);
        assertFalse(d.mayIncrease());
        assertFalse(d.sources().get(0).passes());
    }

    @Test
    void unmeasuredCostLeavesTheGateOpen() {
        // No fill in this feed mode yet ⇒ no cost to compare against. Asserting a cost we have not
        // measured would be a number without provenance, so the gate stands down entirely.
        var d = EdgeGate.evaluate(List.of(stat("momentum", 233, -51.26, 854.12)), null, P);
        assertTrue(d.mayIncrease());
        assertTrue(d.sources().isEmpty());
    }

    @Test
    void coldTelemetryLeavesTheGateOpen() {
        assertTrue(EdgeGate.evaluate(List.of(), 10.0, P).mayIncrease());
        assertTrue(EdgeGate.evaluate(null, 10.0, P).mayIncrease());
    }

    @Test
    void zeroDispersionCannotManufactureSignificance() {
        // A degenerate sample (every return identical) has no standard error; it must not divide by
        // zero into an infinite t-stat.
        var d = EdgeGate.evaluate(List.of(stat("learned", 200, 50.0, 0.0)), 10.0, P);
        assertFalse(d.mayIncrease());
        assertEquals(0.0, d.sources().get(0).tStat(), 1e-9);
    }

    // ---- ADR-0075: the same test, at the granularity cost is actually incurred, on BOTH sides -----

    /** The desk's own measured TCA shape, converted to a round trip (2 × one-way). */
    private static final double BLENDED_ROUND_TRIP = 6.9270;   // one-way 3.4635
    private static final java.util.Map<String, Double> COSTS = java.util.Map.of(
            "ES", 0.29122,       // one-way 0.14561
            "MSFT", 2.92748,     // one-way 1.46374
            "GOOGL", 20.10561);  // one-way 10.05281

    /**
     * The change ADR-0075 makes, in one case. A source measured at +5.00 bps gross with se 2.00:
     * <pre>
     *   blended round trip 6.92700 → net −1.92700 → t = −0.9635  ✗ (the old desk-wide verdict: SHUT)
     *   ES     round trip 0.29122 → net +4.70878 → t = +2.3544  ✓ open, and ES may be increased
     *   MSFT   round trip 2.92748 → net +2.07252 → t = +1.0363  ✗ reduce-only
     *   GOOGL  round trip 20.10561 → net −15.10561 → t = −7.5528 ✗ reduce-only
     *   JNJ    never filled → charged the blend → t = −0.9635   ✗ reduce-only
     * </pre>
     * A real, executable edge — it survives a 0.29 bp round trip with better than 2 standard errors —
     * was being refused everywhere because the AVERAGE name costs twenty times what the name it would
     * actually trade in costs. Nothing else moves: the unmeasured name faces exactly the bar it faced
     * before, and the expensive names are refused as they always were.
     */
    @Test
    void anEdgeThatSurvivesACheapNameOpensThatNameAndOnlyThatName() {
        var d = EdgeGate.evaluate(List.of(stat("reversion", 36, 5.0, 12.0)), BLENDED_ROUND_TRIP, COSTS, P);
        assertTrue(d.mayIncrease());
        assertEquals(2.0, d.sources().get(0).stdErrorBps(), 1e-9);
        assertEquals(2.3544, d.sources().get(0).tStat(), 1e-4);   // stated at the cheapest round trip
        assertEquals(4.70878, d.sources().get(0).netEdgeBps(), 1e-9);
        assertTrue(d.mayIncrease("ES"));
        assertFalse(d.mayIncrease("MSFT"));
        assertFalse(d.mayIncrease("GOOGL"));
        assertFalse(d.mayIncrease("JNJ"));
        // ...and the blended test alone would have shut the gate on the same measurements.
        assertFalse(EdgeGate.evaluate(List.of(stat("reversion", 36, 5.0, 12.0)), BLENDED_ROUND_TRIP, P)
                .mayIncrease());
    }

    /**
     * The other half of the same rule. A source measured at +18.00 bps gross, se 5.00:
     * <pre>
     *   ES     round trip 0.29122 → net +17.70878 → t = +3.5418  ✓
     *   MSFT   round trip 2.92748 → net +15.07252 → t = +3.0145  ✓
     *   GOOGL  round trip 20.10561 → net −2.10561 → t = −0.4211  ✗
     * </pre>
     * GOOGL is a certain loss on every round trip at the very expectancy that opened the gate — the
     * trade the per-name veto exists to refuse (ADR-0072), unchanged.
     */
    @Test
    void anOpenGateStillRefusesANameThatCostsMoreThanTheEdge() {
        var d = EdgeGate.evaluate(List.of(stat("reversion", 36, 18.0, 30.0)), BLENDED_ROUND_TRIP, COSTS, P);
        assertTrue(d.mayIncrease());
        assertEquals(3.5418, d.sources().get(0).tStat(), 1e-4);
        assertTrue(d.mayIncrease("ES"));
        assertTrue(d.mayIncrease("MSFT"));
        assertFalse(d.mayIncrease("GOOGL"));
    }

    /**
     * An expensive name is now judged on significance, not on a raw comparison of means — strictly
     * tighter than the ADR-0072 veto it replaces. Cost 17.00 against a mean of 18.00: the old rule
     * admitted it (18.00 > 17.00), but 1.00 bps of surplus on a 5.00 bps standard error is t = 0.20.
     * A round trip that eats 94% of the measured edge is not a trade the measurement supports.
     */
    @Test
    void aNameThatEatsMostOfTheEdgeIsRefusedEvenThoughTheRawMeanExceedsItsCost() {
        var d = EdgeGate.evaluate(List.of(stat("reversion", 36, 18.0, 30.0)), BLENDED_ROUND_TRIP,
                java.util.Map.of("ES", 0.29122, "SAP", 17.0), P);
        assertTrue(d.mayIncrease());
        assertTrue(d.mayIncrease("ES"));
        assertFalse(d.mayIncrease("SAP"));
    }

    @Test
    void anUnmeasuredNameFacesTheDeskBlendNotAnInventedCost() {
        // JNJ has never filled in this feed mode, so it has no cost of its own. It is charged the
        // desk's measured blend — the same bar it faced before ADR-0075 — never an invented number.
        var d = EdgeGate.evaluate(List.of(stat("reversion", 36, 18.0, 30.0)), BLENDED_ROUND_TRIP,
                java.util.Map.of("GOOGL", 20.10561), P);
        assertTrue(d.mayIncrease("JNJ"));   // (18 − 6.9270)/5 = 2.2146 ≥ 2
        assertTrue(d.mayIncrease(null));
        assertFalse(d.mayIncrease("GOOGL"));
    }

    @Test
    void aShutGateVetoesEveryNameIncludingCheapOnes() {
        // A measured-NEGATIVE source cannot clear any cost, however cheap: at a 0.29 bps round trip
        // its surplus is still −17.60. No cost cross-section can rescue a source that loses money.
        var d = EdgeGate.evaluate(List.of(stat("trend", 69, -17.31, 31.53)), BLENDED_ROUND_TRIP,
                java.util.Map.of("ES", 0.29122), P);
        assertFalse(d.mayIncrease());
        assertFalse(d.mayIncrease("ES"));
    }

    @Test
    void aThinSampleCannotOpenACheapNameEither() {
        // "social" has the loudest mean but only 12 observations. The minimum sample binds per name
        // exactly as it binds desk-wide — a cheap round trip is not a substitute for evidence.
        var d = EdgeGate.evaluate(List.of(stat("social", 12, 40.0, 20.0)), BLENDED_ROUND_TRIP, COSTS, P);
        assertFalse(d.mayIncrease());
        assertFalse(d.mayIncrease("ES"));
    }

    // ---- ADR-0081: the hurdle is read against the distribution the statistic actually follows ------

    /** Stats whose standard error is estimated from a stated number of emission cohorts (ADR-0077). */
    private static SignalScoring.Stats cohortStat(String source, long resolved, double meanBps,
                                                  long cohorts, double stdCohortMeanBps) {
        return new SignalScoring.Stats(source, resolved, 0, 0, 0, 0, 0.0, meanBps, stdCohortMeanBps,
                cohorts, stdCohortMeanBps);
    }

    /**
     * The change ADR-0081 makes, as a controlled pair. Both sources are measured at +13.50 bps with an
     * identical standard error of 5.20, so both produce the IDENTICAL t-statistic of 2.50 against a
     * 0.50 bps round trip. The only difference is how many independent draws that standard error was
     * estimated from:
     * <pre>
     *   4 cohorts   → se = 10.40/√4  = 5.20 → t = 13.00/5.20 = 2.50, df = 3  → p ≈ 0.0438 &gt; α  ✗
     *   100 cohorts → se = 52.00/√100 = 5.20 → t = 13.00/5.20 = 2.50, df = 99 → p ≈ 0.0071 &lt; α  ✓
     * </pre>
     * The old rule compared 2.50 against a fixed 2.00 and opened the gate in BOTH cases. Three or four
     * draws of the market cannot support a 97.7% claim, however wide the cross-section they span, and
     * this is exactly the shape the live desk presents: a source with dozens of resolved observations
     * that are only a handful of independent cohorts.
     */
    @Test
    void theSameTStatIsEvidenceFromManyDrawsAndNotFromFour() {
        var thin = EdgeGate.evaluate(List.of(cohortStat("reversion", 92, 13.5, 4, 10.4)), 0.5, P);
        var deep = EdgeGate.evaluate(List.of(cohortStat("reversion", 100, 13.5, 100, 52.0)), 0.5, P);

        assertEquals(5.2, thin.sources().get(0).stdErrorBps(), 1e-12);
        assertEquals(5.2, deep.sources().get(0).stdErrorBps(), 1e-12);
        assertEquals(2.5, thin.sources().get(0).tStat(), 1e-12);
        assertEquals(2.5, deep.sources().get(0).tStat(), 1e-12);

        assertFalse(thin.mayIncrease());
        assertTrue(deep.mayIncrease());
        assertTrue(thin.sources().get(0).pValue() > P.alpha());
        assertTrue(deep.sources().get(0).pValue() < P.alpha());
    }

    /**
     * The dial keeps its meaning exactly: t-hurdle 2.0 asserts a one-sided α of 0.02275, and in the
     * large-sample limit the Student-t test IS the normal test the gate used to run. So this correction
     * can only ever bite where the normal approximation was invalid — it is a no-op everywhere else.
     */
    @Test
    void theHurdleIsUnchangedOnceTheSampleIsLargeEnoughForItToHaveBeenRight() {
        assertEquals(0.02275, P.alpha(), 1e-5);
        // A t of 2.05 — a whisker over the dial — on 10,000 cohorts: se = 1000/√10000 = 10.
        var deep = EdgeGate.evaluate(List.of(cohortStat("trend", 10_000, 20.505, 10_000, 1000.0)), 0.005, P);
        assertEquals(2.05, deep.sources().get(0).tStat(), 1e-12);
        assertTrue(deep.mayIncrease());
        // ...and the identical statistic on four cohorts is not evidence. Same t, same dial, same α.
        var thin = EdgeGate.evaluate(List.of(cohortStat("trend", 92, 20.505, 4, 20.0)), 0.005, P);
        assertEquals(2.05, thin.sources().get(0).tStat(), 1e-12);
        assertFalse(thin.mayIncrease());
    }

    /**
     * A single cross-section supports no standard error at all (ADR-0077 sets it to zero there), and one
     * cohort has zero degrees of freedom. Both readings must land on "no evidence" rather than on a
     * division that manufactures certainty from one draw of the market.
     */
    @Test
    void oneCohortIsNeverEvidenceHoweverWideItIs() {
        var d = EdgeGate.evaluate(List.of(cohortStat("reversion", 200, 400.0, 1, 0.0)), 0.5, P);
        assertFalse(d.mayIncrease());
        assertFalse(d.sources().get(0).passes());
        assertEquals(1.0, d.sources().get(0).pValue(), 1e-12);
    }

    /**
     * The per-name test (ADR-0075) is the same test, so it inherits the same correction: a name whose
     * own round trip is cheap enough to flip the arithmetic still cannot be opened on four draws.
     */
    @Test
    void thePerNameTestInheritsTheCorrection() {
        var d = EdgeGate.evaluate(List.of(cohortStat("reversion", 92, 13.5, 4, 10.4)),
                BLENDED_ROUND_TRIP, COSTS, P);
        assertFalse(d.mayIncrease());
        assertFalse(d.mayIncrease("ES"));      // t = (13.5 − 0.29122)/5.2 = 2.54 on df 3 → not evidence
        assertFalse(d.mayIncrease("GOOGL"));
    }

    /**
     * Evidence is ordered by its tail probability, not by its t-statistic: across sources with different
     * cohort counts those disagree, and the p-value is the comparable one. Here the weaker-looking t of
     * 2.10 on 100 cohorts is far stronger evidence than 2.50 on 4, and must lead the list.
     */
    @Test
    void theEvidenceListLeadsWithTheBestEvidencedSourceNotTheLoudestTStat() {
        var d = EdgeGate.evaluate(List.of(
                cohortStat("reversion", 92, 13.5, 4, 10.4),      // t = 2.50, df 3
                cohortStat("momentum", 100, 11.42, 100, 52.0)),  // t = 2.10, df 99
                0.5, P);
        assertEquals("momentum", d.sources().get(0).source());
        assertTrue(d.sources().get(0).tStat() < d.sources().get(1).tStat());
        assertTrue(d.mayIncrease());
    }

    @Test
    void reduceOnlyProjectionNeverGrowsAPosition() {
        BigDecimal longPos = new BigDecimal("100");
        BigDecimal shortPos = new BigDecimal("-100");
        // adding to a long / to a short → nothing
        assertEquals(0, TargetPlanner.reduceOnly(new BigDecimal("40"), longPos).signum());
        assertEquals(0, TargetPlanner.reduceOnly(new BigDecimal("-40"), shortPos).signum());
        // trimming is allowed, at its full size
        assertEquals(new BigDecimal("-40.000000"), TargetPlanner.reduceOnly(new BigDecimal("-40"), longPos));
        assertEquals(new BigDecimal("40.000000"), TargetPlanner.reduceOnly(new BigDecimal("40"), shortPos));
        // a flip is truncated at flat — closing 100 is a reduction, the extra 150 would be a new position
        assertEquals(new BigDecimal("-100.000000"), TargetPlanner.reduceOnly(new BigDecimal("-250"), longPos));
        assertEquals(new BigDecimal("100.000000"), TargetPlanner.reduceOnly(new BigDecimal("250"), shortPos));
        // opening from flat is an increase
        assertEquals(0, TargetPlanner.reduceOnly(new BigDecimal("250"), BigDecimal.ZERO).signum());
        assertEquals(0, TargetPlanner.reduceOnly(null, longPos).signum());
    }
}
