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

    // ---- ADR-0072: the same test, at the granularity cost is actually incurred -------------------

    /**
     * The desk's OWN measured TCA on 2026-07-26, converted to a round trip (2 x one-way), against a
     * source measured at +18.00 bps gross that clears the blended hurdle:
     * <pre>
     *   blended one-way 3.4635 bps → round trip  6.9270 → net +11.073 → gate OPEN
     *   ES     one-way 0.14561 bps → round trip  0.29122 → keep 18.00 − 0.29122 = +17.709 bps  ✓
     *   MSFT   one-way 1.46374 bps → round trip  2.92748 → keep 18.00 − 2.92748 = +15.073 bps  ✓
     *   GOOGL  one-way 10.05281 bps → round trip 20.10561 → keep 18.00 − 20.10561 = −2.106 bps ✗
     * </pre>
     * The blended hurdle admits all three; GOOGL is a certain loss on every round trip at the very
     * expectancy that opened the gate. That is the trade this veto exists to refuse.
     */
    @Test
    void anOpenGateStillRefusesANameThatCostsMoreThanTheEdge() {
        var costs = java.util.Map.of(
                "ES", 0.29122,
                "MSFT", 2.92748,
                "GOOGL", 20.10561);
        // n = 36, sd 30 → se = 5.0; net of the blended 6.9270 = 11.073 → t = 2.2146 ≥ 2 → open.
        var d = EdgeGate.evaluate(List.of(stat("reversion", 36, 18.0, 30.0)), 6.9270, costs, P);
        assertTrue(d.mayIncrease());
        assertEquals(2.2146, d.sources().get(0).tStat(), 1e-4);
        assertEquals(18.0, d.bestGrossEdgeBps(), 1e-9);
        assertTrue(d.mayIncrease("ES"));
        assertTrue(d.mayIncrease("MSFT"));
        assertFalse(d.mayIncrease("GOOGL"));
    }

    @Test
    void anUnmeasuredNameIsNotVetoedOnAnAssumedCost() {
        // JNJ has never filled in this feed mode, so there is no cost to compare against. The gate
        // asserts none rather than inventing one — the desk-wide verdict stands alone for that name.
        var d = EdgeGate.evaluate(List.of(stat("reversion", 36, 18.0, 30.0)), 6.9270,
                java.util.Map.of("GOOGL", 20.10561), P);
        assertTrue(d.mayIncrease("JNJ"));
        assertTrue(d.mayIncrease(null));
        assertFalse(d.mayIncrease("GOOGL"));
    }

    @Test
    void aShutGateVetoesEveryNameIncludingCheapOnes() {
        // No source passed, so there is no edge to spend anywhere — a 0.29 bps round trip is still
        // paying for nothing. Per-name permission can only ever subtract from the desk-wide verdict.
        var d = EdgeGate.evaluate(List.of(stat("trend", 69, -17.31, 31.53)), 6.9270,
                java.util.Map.of("ES", 0.29122), P);
        assertFalse(d.mayIncrease());
        assertFalse(d.mayIncrease("ES"));
        assertEquals(0.0, d.bestGrossEdgeBps(), 1e-9);
    }

    @Test
    void theClaimableEdgeComesFromAPassingSourceNotTheLoudestMean() {
        // "social" has the bigger mean but only 12 observations, so it never passed and its 40 bps is
        // not an edge the desk may spend. The claimable edge is the passing source's 18.00, which is
        // NOT enough to pay GOOGL's 20.11 round trip.
        var d = EdgeGate.evaluate(List.of(
                        stat("social", 12, 40.0, 20.0),
                        stat("reversion", 36, 18.0, 30.0)),
                6.9270, java.util.Map.of("GOOGL", 20.10561), P);
        assertTrue(d.mayIncrease());
        assertEquals(18.0, d.bestGrossEdgeBps(), 1e-9);
        assertFalse(d.mayIncrease("GOOGL"));
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
