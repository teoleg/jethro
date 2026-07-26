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
