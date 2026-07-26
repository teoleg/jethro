package io.jethro.app.fusion;

import io.jethro.app.signal.SignalScoring;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ADR-0067 evidence-scaled edge gate (superseding ADR-0064's binary switch). Every case is a
 * worked example: measured expectancy and dispersion in, a t-stat against measured round-trip cost
 * out, and from it a risk appetite Φ(t) that scales the book — with a full stop reserved for a source
 * whose expectancy is significantly BELOW its cost.
 */
class EdgeGateTest {

    private static final EdgeGate.Params P = new EdgeGate.Params(30, 2.0);

    /** Stats with an explicit mean/dispersion — the only two fields the gate reads. */
    private static SignalScoring.Stats stat(String source, long n, double meanBps, double stdBps) {
        return new SignalScoring.Stats(source, n, 0, 0, 0, 0, 0.0, meanBps, stdBps);
    }

    // ---------------------------------------------------------------- Φ, the appetite's only maths

    @Test
    void theNormalCdfMatchesItsTable() {
        // A&S 26.2.17 claims |ε| < 7.5e-8; assert against the textbook values well inside that.
        assertEquals(0.500000000, EdgeGate.standardNormalCdf(0.0), 1e-9);
        assertEquals(0.841344746, EdgeGate.standardNormalCdf(1.0), 1e-7);
        assertEquals(0.158655254, EdgeGate.standardNormalCdf(-1.0), 1e-7);
        assertEquals(0.975002105, EdgeGate.standardNormalCdf(1.96), 1e-7);
        assertEquals(0.977249868, EdgeGate.standardNormalCdf(2.0), 1e-7);
        assertEquals(0.022750132, EdgeGate.standardNormalCdf(-2.0), 1e-7);
        assertEquals(0.998650102, EdgeGate.standardNormalCdf(3.0), 1e-7);
        assertEquals(0.999968329, EdgeGate.standardNormalCdf(4.0), 1e-7);
    }

    @Test
    void theAppetiteNeverEscapesItsUnitInterval() {
        // Saturation at both tails — an extreme t must not size a book outside [0,1] of the notional.
        assertEquals(1.0, EdgeGate.standardNormalCdf(1e9), 1e-12);
        assertEquals(0.0, EdgeGate.standardNormalCdf(-1e9), 1e-12);
        assertTrue(EdgeGate.standardNormalCdf(-45.0) >= 0.0);
        assertEquals(0.5, EdgeGate.standardNormalCdf(Double.NaN), 1e-12);
    }

    // ---------------------------------------------------------------- absence of evidence ≠ harm

    @Test
    void anUnimpressiveSourceIsSizedDownNotSwitchedOff() {
        // mean −51.26 bps, sd 854.12, n 233 → se = 854.12/√233 = 55.96; net of 10bps cost = −61.26
        // t = −61.26/55.96 = −1.095. That is NOT significant harm (|t| < 2), so the desk may still
        // take risk — at Φ(−1.0948) = 0.1368 of the configured notional, which is what the evidence
        // supports. ADR-0064 stopped the desk dead here; that was the bug.
        var d = EdgeGate.evaluate(List.of(stat("momentum", 233, -51.26, 854.12)), 10.0, P);
        assertTrue(d.mayIncrease());
        assertEquals(-1.0948, d.sources().get(0).tStat(), 1e-4);
        assertEquals(-61.26, d.sources().get(0).netEdgeBps(), 1e-9);
        assertEquals(0.136802, d.riskAppetite(), 1e-6);
    }

    @Test
    void aPositiveButInsignificantMeanSizesAboveHalfButNowhereNearFull() {
        // +40 bps looks like a winner until the dispersion is read: se = 900/√100 = 90, net = 30,
        // t = 1/3 → Φ(0.333333…) = 0.630559. Evidence that leans positive, sized as a lean, not a fact.
        var d = EdgeGate.evaluate(List.of(stat("mean-reversion", 100, 40.0, 900.0)), 10.0, P);
        assertTrue(d.mayIncrease());
        assertEquals(1.0 / 3.0, d.sources().get(0).tStat(), 1e-9);
        assertEquals(0.630558660, d.riskAppetite(), 1e-7);
    }

    @Test
    void aSourceThatBeatsCostWithSignificanceEarnsTheFullConfiguredSize() {
        // mean +30 bps, sd 60, n 144 → se = 5.0; net of 10bps cost = 20 → t = 4.0 → Φ = 0.999968.
        var d = EdgeGate.evaluate(List.of(stat("hypothesis", 144, 30.0, 60.0)), 10.0, P);
        assertTrue(d.mayIncrease());
        assertEquals(4.0, d.sources().get(0).tStat(), 1e-9);
        assertEquals(0.999968, d.riskAppetite(), 1e-6);
        assertTrue(d.reason().contains("hypothesis"));
    }

    @Test
    void theBestEvidencedSourceSetsTheAppetite() {
        var d = EdgeGate.evaluate(List.of(
                stat("momentum", 233, -51.26, 854.12),
                stat("hypothesis", 144, 30.0, 60.0)), 10.0, P);
        assertTrue(d.mayIncrease());
        // sorted by t-stat: the winner leads the evidence list, and sets the size for the book
        assertEquals("hypothesis", d.sources().get(0).source());
        assertEquals(0.999968, d.riskAppetite(), 1e-6);
    }

    // ---------------------------------------------------------------- demonstrated harm still stops

    @Test
    void measuredHarmIsStillAFullStop() {
        // Same source, same statistics: gross it clears the hurdle, net of a 30bps round trip its
        // expectancy is significantly BELOW cost (t = −2.0) — the case ADR-0064 was built for.
        var s = stat("mean-reversion", 400, 20.0, 100.0); // se = 5.0
        assertTrue(EdgeGate.evaluate(List.of(s), 0.0, P).mayIncrease());   // t = 20/5 = 4.0
        var harmed = EdgeGate.evaluate(List.of(s), 30.0, P);               // t = −10/5 = −2.0
        assertFalse(harmed.mayIncrease());
        assertEquals(-2.0, harmed.sources().get(0).tStat(), 1e-9);
        assertEquals(0.022750, harmed.riskAppetite(), 1e-6);
        assertTrue(harmed.reason().contains("reduce-only"));
    }

    @Test
    void aMeasuredWinnerRescuesTheBookFromALoser() {
        // Reduce-only asks about the BEST evidence, not the worst: one source deep under water must
        // not stop a desk that has another source measurably clearing its cost.
        var d = EdgeGate.evaluate(List.of(
                stat("mean-reversion", 400, -40.0, 100.0),  // t = (−40−10)/5 = −10 → deep harm
                stat("hypothesis", 144, 30.0, 60.0)), 10.0, P);
        assertTrue(d.mayIncrease());
        assertEquals(0.999968, d.riskAppetite(), 1e-6);
    }

    // ---------------------------------------------------------------- what does NOT count as evidence

    @Test
    void aThinSampleNeitherRaisesNorLowersTheAppetite() {
        // n = 12 (< minSample 30) with a spectacular mean — exactly the lucky-streak case the minimum
        // sample exists to refuse. It does not speak at all, so the appetite stays at Φ(0) = 1/2.
        var d = EdgeGate.evaluate(List.of(stat("social", 12, 515.04, 100.0)), 10.0, P);
        assertTrue(d.mayIncrease());
        assertFalse(d.sources().get(0).passes());
        assertEquals(0.0, d.sources().get(0).tStat(), 1e-9);
        assertEquals(0.5, d.riskAppetite(), 1e-12);
    }

    @Test
    void aThinSampleCannotHideAMeasuredLoser() {
        // The thin sample is ignored; the source that DOES have a sample still sets the appetite.
        var d = EdgeGate.evaluate(List.of(
                stat("social", 12, 515.04, 100.0),
                stat("mean-reversion", 400, 20.0, 100.0)), 30.0, P); // t = −2.0
        assertFalse(d.mayIncrease());
    }

    @Test
    void unmeasuredCostSizesAtTheNoEvidenceAppetite() {
        // No fill in this feed mode yet ⇒ no cost to compare against. Asserting a cost we have not
        // measured would be a number without provenance — so this is an absence of evidence and sizes
        // like one: half the configured notional, not a full stop and not full size.
        var d = EdgeGate.evaluate(List.of(stat("momentum", 233, -51.26, 854.12)), null, P);
        assertTrue(d.mayIncrease());
        assertTrue(d.sources().isEmpty());
        assertEquals(0.5, d.riskAppetite(), 1e-12);
    }

    @Test
    void coldTelemetrySizesAtTheNoEvidenceAppetite() {
        for (var d : List.of(EdgeGate.evaluate(List.of(), 10.0, P), EdgeGate.evaluate(null, 10.0, P))) {
            assertTrue(d.mayIncrease());
            assertEquals(0.5, d.riskAppetite(), 1e-12);
        }
    }

    @Test
    void zeroDispersionCannotManufactureSignificance() {
        // A degenerate sample (every return identical) has no standard error; it must not divide by
        // zero into an infinite t-stat, and it is not evidence in either direction.
        var d = EdgeGate.evaluate(List.of(stat("learned", 200, 50.0, 0.0)), 10.0, P);
        assertTrue(d.mayIncrease());
        assertEquals(0.0, d.sources().get(0).tStat(), 1e-9);
        assertEquals(0.5, d.riskAppetite(), 1e-12);
    }

    // ---------------------------------------------------------------- the appetite as a size

    @Test
    void theAppetiteScalesTheNotionalExactlyAndOnlyDownwards() {
        var full = new FusionPlanner.Params(0.5, new BigDecimal("50000"), 0.5, 0.5);
        // Worked example: $50,000 at the no-evidence appetite Φ(0) = 0.500000 → $25,000.000000.
        assertEquals(new BigDecimal("25000.000000"), full.scaledBy(0.5).unitNotional());
        // A measured winner (t = +4 → Φ = 0.999968329, quantised to 0.999968) → $49,998.400000.
        assertEquals(new BigDecimal("49998.400000"), full.scaledBy(0.999968329).unitNotional());
        // A measured loser (t = −1 → Φ = 0.158655254 → 0.158655) → $7,932.750000.
        assertEquals(new BigDecimal("7932.750000"), full.scaledBy(0.158655254).unitNotional());
        // It can never grow the size the owner configured, whatever it is handed.
        assertEquals(full.unitNotional(), full.scaledBy(1.0).unitNotional());
        assertEquals(full.unitNotional(), full.scaledBy(7.5).unitNotional());
        assertEquals(0, full.scaledBy(0.0).unitNotional().signum());
        assertEquals(0, full.scaledBy(-3.0).unitNotional().signum());
        assertEquals(0, full.scaledBy(Double.NaN).unitNotional().signum());
        // Everything else in the pass is untouched.
        assertEquals(0.5, full.scaledBy(0.25).bufferFraction(), 1e-12);
        assertEquals(0.5, full.scaledBy(0.25).adjustmentRate(), 1e-12);
        assertEquals(0.5, full.scaledBy(0.25).assumedCorrelation(), 1e-12);
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
