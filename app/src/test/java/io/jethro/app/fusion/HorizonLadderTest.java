package io.jethro.app.fusion;

import io.jethro.app.signal.SignalScoring;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0082: the desk measures its edge at a ladder of horizons and lets the data pick which one it
 * trades on. Every case is a worked example — measured expectancy at each rung against a measured round
 * trip, and the rung the evidence selects.
 *
 * <p>The two cases that matter are {@link #aSlowEdgeKeepsTheBaseRungBecauseCostDoesNotShrinkWithIt} and
 * {@link #aFastEdgeSelectsTheShortRungItIsActuallyRealisedOver}. Together they are the argument that the
 * ladder is a measurement and not a way to buy significance: shortening the horizon shrinks the
 * expectancy credited against a round-trip cost that does not shrink at all, so a short rung is only
 * ever selected when the edge is genuinely realised inside it.
 */
class HorizonLadderTest {

    /** Two rungs searched ⇒ α is halved (Bonferroni). minSample 2 so the sample size is not the subject. */
    private static final EdgeGate.Params P2 = new EdgeGate.Params(2, 2.0, 2);

    /** The desk's cheapest measured round trip in these examples. */
    private static final double ROUND_TRIP_BPS = 2.0;

    private static SignalScoring.Stats rung(String source, long resolved, double meanBps, long cohorts,
                                            double stdCohortMeanBps, long horizonSeconds) {
        return new SignalScoring.Stats(source, resolved, 0, 0, 0, 0, 0.0, meanBps, stdCohortMeanBps,
                cohorts, stdCohortMeanBps, horizonSeconds);
    }

    /** A ladder map in the order the telemetry produces it — longest rung first. */
    private static Map<Integer, List<SignalScoring.Stats>> ladder(int longSeconds,
                                                                  List<SignalScoring.Stats> longRung,
                                                                  int shortSeconds,
                                                                  List<SignalScoring.Stats> shortRung) {
        var out = new LinkedHashMap<Integer, List<SignalScoring.Stats>>();
        out.put(longSeconds, longRung);
        out.put(shortSeconds, shortRung);
        return out;
    }

    // ---- the ladder's geometry -------------------------------------------------------------------

    @Test
    void theLadderIsGeometricDownFromTheBaseAndAlwaysContainsIt() {
        assertEquals(List.of(3600, 900, 225), HorizonLadder.rungs(3600, 3));
        assertEquals(List.of(3600), HorizonLadder.rungs(3600, 1), "one rung is the pre-ADR-0082 config");
        assertEquals(List.of(3600, 900), HorizonLadder.rungs(3600, 2));
    }

    @Test
    void theLadderBottomsOutRatherThanEmittingSubSecondOrDuplicateRungs() {
        // 8 → 2 → (2/4 = 0, below one second) stop. Three rungs asked for, two honest ones returned.
        assertEquals(List.of(8, 2), HorizonLadder.rungs(8, 3));
        assertEquals(List.of(1), HorizonLadder.rungs(1, 5));
        assertEquals(List.of(1), HorizonLadder.rungs(0, 3), "a non-positive base floors at one second");
    }

    // ---- the search is paid for ------------------------------------------------------------------

    @Test
    void searchingMRungsDividesTheConfidenceByM() {
        double single = new EdgeGate.Params(30, 2.0).alpha();
        // 1 − Φ(2) = 0.02275013…; the desk's single Φ is a rational approximation, hence the tolerance.
        assertEquals(0.0227501319, single, 1e-6, "tHurdle 2.0 is α = 1 − Φ(2) — unchanged (ADR-0081)");
        assertEquals(single / 3.0, new EdgeGate.Params(30, 2.0, 3).alpha(), 1e-12);
        assertEquals(single, new EdgeGate.Params(30, 2.0, 1).alpha(), 1e-12,
                "one hypothesis is no multiplicity — exactly the pre-ADR-0082 gate");
    }

    /**
     * The haircut can only ever make the gate HARDER to open, never easier — the property that makes
     * this change safe to ship on a desk whose only ✅ GOOD verdict came from trading less.
     *
     * <p>Worked: mean 14.00 bps, 40 cohorts, sd(cohort means) 34.00 ⇒ se = 34.00/√40 = 5.3759; net of a
     * 2.00 bps round trip = 12.00; t = 12.00/5.3759 = 2.2322 on 39 df ⇒ p ≈ 0.0155. That falls inside
     * α = 0.02275 when this is the only hypothesis, and outside α/3 = 0.00758 once three horizons were
     * searched to find it. Same evidence, two verdicts — and the stricter one is the honest one.
     */
    @Test
    void aRungThatClearedTheSingleHypothesisBarNeedNotClearTheSearchedOne() {
        // mean 14.00, 40 cohorts, sd 34.00 ⇒ se = 5.3759, net = 12.00, t = 2.2321 on 39 df.
        var s = List.of(rung("reversion", 400, 14.00, 40, 34.00, 3600));
        assertTrue(EdgeGate.evaluate(s, ROUND_TRIP_BPS, new EdgeGate.Params(2, 2.0, 1)).mayIncrease(),
                "clears when it is the only hypothesis");
        assertFalse(EdgeGate.evaluate(s, ROUND_TRIP_BPS, new EdgeGate.Params(2, 2.0, 3)).mayIncrease(),
                "the same evidence does not clear once three horizons were searched to find it");
    }

    // ---- the two cases the ladder exists to tell apart --------------------------------------------

    /**
     * A SLOW edge: expectancy accrues with time, so it scales down with the horizon while the round trip
     * charged against it does not move at all. The short rung has every statistical advantage — 48
     * cohorts against 3, a standard error four times smaller — and still loses, because the money is
     * not there.
     *
     * <pre>
     *   3600 s: mean 10.000, 3 cohorts,  sd 1.000 ⇒ se = 0.5774, net = 10.000 − 2 = 8.000
     *           t = 13.856 on 2 df                                        ⇒ p = 0.00258  ≤ α/2  ✓
     *    225 s: mean  0.625 (= 10/16),  48 cohorts, sd 1.000 ⇒ se = 0.1443, net = 0.625 − 2 = −1.375
     *           t = −9.526 on 47 df                                       ⇒ p ≈ 1        > α/2  ✗
     * </pre>
     */
    @Test
    void aSlowEdgeKeepsTheBaseRungBecauseCostDoesNotShrinkWithIt() {
        var sel = HorizonLadder.select(
                ladder(3600, List.of(rung("reversion", 69, 10.000, 3, 1.000, 3600)),
                        225, List.of(rung("reversion", 1104, 0.625, 48, 1.000, 225))),
                ROUND_TRIP_BPS, Map.of(), P2);

        assertEquals(3600L, sel.horizonSeconds());
        assertTrue(sel.evidenced());
        assertTrue(sel.decision().mayIncrease());
        assertEquals(8.000, sel.decision().sources().get(0).netEdgeBps(), 1e-9);
        assertEquals(3600L, sel.decision().horizonSeconds(), "the decision states the period it judged");
    }

    /**
     * A FAST edge: the move is realised inside four minutes and the rest of the hour adds only noise.
     * The net expectancy per round trip is identical at both rungs, so the ladder picks the one with the
     * evidence — and picking it is a real improvement, because the same net edge is captured sixteen
     * times as often.
     *
     * <pre>
     *   3600 s: mean 10.000,  3 cohorts, sd 6.000 ⇒ se = 3.4641, net = 8.000
     *           t = 2.3094 on 2 df                                ⇒ p = 0.07360  > α/2  ✗
     *    225 s: mean 10.000, 48 cohorts, sd 6.000 ⇒ se = 0.8660, net = 8.000
     *           t = 9.2376 on 47 df                               ⇒ p ≈ 2.2e-12  ≤ α/2  ✓
     * </pre>
     */
    @Test
    void aFastEdgeSelectsTheShortRungItIsActuallyRealisedOver() {
        var sel = HorizonLadder.select(
                ladder(3600, List.of(rung("reversion", 69, 10.000, 3, 6.000, 3600)),
                        225, List.of(rung("reversion", 1104, 10.000, 48, 6.000, 225))),
                ROUND_TRIP_BPS, Map.of(), P2);

        assertEquals(225L, sel.horizonSeconds());
        assertTrue(sel.evidenced());
        assertTrue(sel.decision().mayIncrease());
        assertEquals(225L, sel.decision().horizonSeconds());
        // …and the stats handed to the weighting layer are that rung's, not the base rung's, so a
        // source is graded, weighted and held over one and the same period.
        assertEquals(48, sel.stats().get(0).cohorts());
    }

    // ---- fallbacks ---------------------------------------------------------------------------------

    @Test
    void whenNoRungClearsTheBaseStands() {
        var sel = HorizonLadder.select(
                ladder(3600, List.of(rung("trend", 161, -11.59, 7, 10.95, 3600)),
                        225, List.of(rung("trend", 2576, -0.72, 112, 10.95, 225))),
                ROUND_TRIP_BPS, Map.of(), P2);

        assertEquals(3600L, sel.horizonSeconds(), "the desk's stated default measurement");
        assertFalse(sel.evidenced());
        assertFalse(sel.decision().mayIncrease(), "a measurably losing source is reduce-only at every rung");
        assertEquals(1, sel.stats().size());
    }

    @Test
    void tiedEvidenceBreaksTowardTheLongerHorizonBecauseItPaysFewerRoundTrips() {
        var identical = List.of(rung("reversion", 400, 10.000, 40, 6.000, 0));
        var sel = HorizonLadder.select(
                ladder(3600, List.of(identical.get(0).atHorizon(3600)),
                        900, List.of(identical.get(0).atHorizon(900))),
                ROUND_TRIP_BPS, Map.of(), P2);

        assertEquals(3600L, sel.horizonSeconds());
    }

    @Test
    void withNoMeasuredCostTheGateIsInactiveAndTheBaseRungStands() {
        var sel = HorizonLadder.select(
                ladder(3600, List.of(rung("reversion", 69, 10.000, 3, 1.000, 3600)),
                        225, List.of(rung("reversion", 1104, 10.000, 48, 1.000, 225))),
                null, Map.of(), P2);

        assertEquals(3600L, sel.horizonSeconds());
        assertFalse(sel.evidenced(), "an open-because-uninstrumented gate is not evidence of an edge");
        assertTrue(sel.decision().mayIncrease(), "…but the pre-existing controls still stand alone");
        assertEquals(1, sel.stats().size(), "the base rung's stats still drive the weights");
    }

    @Test
    void anEmptyLadderIsHandledWithoutAHorizonClaim() {
        var sel = HorizonLadder.select(Map.of(), ROUND_TRIP_BPS, Map.of(), P2);
        assertEquals(0L, sel.horizonSeconds());
        assertFalse(sel.evidenced());
        assertTrue(sel.stats().isEmpty());
    }

    // ---- the holding period follows the selected horizon (ADR-0080's identity, ADR-0082's horizon) --

    /**
     * The identity ADR-0080 established, now evaluated at the rung the evidence chose: the exposure time
     * constant equals the selected horizon exactly, so the desk pays one round trip per horizon of
     * return — the trade the gate priced — whichever rung wins.
     *
     * <pre>
     *   cycle 30 s, horizon 3600 s → a = 1 − e^(−1/120) = 0.0082987074, τ = −30/ln(1−a) = 3600 s
     *   cycle 30 s, horizon  225 s → a = 1 − e^(−2/15)  = 0.1248266808, τ = −30/ln(1−a) =  225 s
     * </pre>
     * The selected rung therefore changes the desk's turnover by the full ladder ratio — sixteen times
     * faster at the bottom rung — which is exactly why the rung must be chosen by measured evidence and
     * not by a dial.
     */
    @Test
    void theHoldingPeriodEqualsWhicheverHorizonWasSelected() {
        for (long horizon : new long[] {3600, 900, 225}) {
            double a = TargetPlanner.adjustmentRateFor(30, horizon);
            double tau = -30.0 / Math.log(1.0 - a);
            assertEquals((double) horizon, tau, 1e-6,
                    "exposure must e-fold toward target in exactly the horizon the edge was measured over");
        }
        assertEquals(0.0082987074, TargetPlanner.adjustmentRateFor(30, 3600), 1e-9);
        assertEquals(0.1248266808, TargetPlanner.adjustmentRateFor(30, 225), 1e-9);
    }

    // ---- ADR-0148: the weighting rung is the best-DETERMINED one ---------------------------------

    @Test
    void theWeightingRungIsTheOneWithTheMostIndependentCohorts() {
        var ladder = ladder(
                3600, List.of(rung("trend", 240, -3.0, 17, 11.0, 3600)),
                225, List.of(rung("trend", 3202, 0.5, 173, 3.2, 225)));
        var selected = ladder.get(3600);
        var weighting = HorizonLadder.weightingStats(ladder, selected);
        assertEquals(225L, weighting.get(0).horizonSeconds(),
                "whose view counts is a directional question — it is answered on the most-replicated rung");
    }

    @Test
    void tiedCohortCountsBreakTowardTheLongerHorizonAsEverywhereElse() {
        var ladder = ladder(
                3600, List.of(rung("trend", 100, 1.0, 40, 5.0, 3600)),
                225, List.of(rung("trend", 900, 1.0, 40, 5.0, 225)));
        assertEquals(3600L, HorizonLadder.weightingStats(ladder, ladder.get(225)).get(0).horizonSeconds());
    }

    @Test
    void aRungWithNoStandardErrorOfItsOwnCannotWinOnCohortCount() {
        // A single cross-section is one draw however wide (ADR-0077), so a rung of one-cohort readings
        // contributes no evidence and must not displace a rung that can actually support a t-statistic.
        var ladder = ladder(
                3600, List.of(rung("trend", 240, -3.0, 17, 11.0, 3600)),
                225, List.of(rung("trend", 9999, 0.5, 1, 3.2, 225)));
        assertEquals(3600L, HorizonLadder.weightingStats(ladder, ladder.get(3600)).get(0).horizonSeconds());
    }

    @Test
    void withNoLadderAtAllTheSelectedRungStandsByteForByte() {
        var selected = List.of(rung("trend", 240, -3.0, 17, 11.0, 3600));
        assertEquals(selected, HorizonLadder.weightingStats(null, selected));
        assertEquals(selected, HorizonLadder.weightingStats(Map.of(), selected));
        assertEquals(selected, HorizonLadder.weightingStats(Map.of(3600, List.of()), selected));
    }

    /**
     * The worked example this change exists for — the desk's OWN live telemetry on 2026-08-11, every
     * figure read from that run's {@code signals_telemetry} and reproduced here to the digit.
     *
     * <p>At the base 3600 s rung the five readings rest on 5–17 cohorts and NOT ONE of them is
     * significant, yet their ranking is what set the live weight vector. At the 225 s rung the same
     * sources carry 5–173 cohorts, and the ranking is very nearly inverted:
     *
     * <pre>
     *   source        3600 s: mean bps / cohorts →   t        225 s: mean bps / cohorts →   t
     *   trend           −3.1970 / 17            → −1.157        +0.5626 / 173           → +2.318
     *   reversion       +1.8534 / 15            → +0.933        −0.4166 / 161           → −1.666
     *   xsreversion     +1.8218 / 11            → +0.737        −0.2475 / 173           → −0.957
     * </pre>
     * Live, the desk therefore held its only positive large-sample source at the 0.25 floor and let the
     * two measured-negative ones steer at ~1.2 — it was trading AGAINST its own best measurement. The
     * assertions below are the exact weights each rung produces, so the inversion is a fact of the code
     * and not a narrative.
     */
    @Test
    void theLiveInversionTheChangeFixes() {
        var p = new TelemetryWeights.Params(20.0, 0.25, 3.0);
        var slow = List.of(
                rung("momentum", 37, 8.907500039682539, 6, 15.861919225934964, 3600),
                rung("reversion", 221, 1.8534239995742232, 15, 7.694708339082848, 3600),
                rung("social", 16, 11.699066420000001, 5, 19.61020839239835, 3600),
                rung("trend", 240, -3.1970467775587132, 17, 11.392266749170606, 3600),
                rung("xsreversion", 214, 1.8217915834928233, 11, 8.194446924978743, 3600));
        var fast = List.of(
                rung("momentum", 70, 0.7658192839803314, 12, 6.642058039876793, 225),
                rung("reversion", 2957, -0.41663262448092314, 161, 3.1724418763212565, 225),
                rung("social", 16, 7.76605068, 5, 14.186033904679068, 225),
                rung("trend", 3202, 0.5625529304740415, 173, 3.192485512477213, 225),
                rung("xsreversion", 3271, -0.24754898223101465, 173, 3.403756164741451, 225));

        // What the live desk did: weights off the base rung, trend pinned at the floor and out-voted.
        var before = TelemetryWeights.compute(slow, p);
        assertEquals(0.25, before.get("trend"), 1e-9, "the only large-sample positive source, at the floor");
        assertTrue(before.get("reversion") > 1.2, "a measured-negative source steering the book");
        assertTrue(before.get("xsreversion") > 1.1, "and the second one alongside it");

        // What ADR-0148 does: the same statistic on the rung that can actually distinguish.
        var ladder = ladder(3600, slow, 225, fast);
        var after = TelemetryWeights.compute(HorizonLadder.weightingStats(ladder, slow), p);
        assertTrue(after.get("trend") > 1.9, "trend now carries the conviction its measurement earned");
        assertEquals(0.25, after.get("reversion"), 1e-9, "and the measured-negative sources fall to the floor");
        assertTrue(after.get("xsreversion") < 0.4);
        assertTrue(after.get("trend") > 5.0 * after.get("reversion"),
                "the ranking is inverted relative to what the desk traded on");

        // The combiner normalises by Σweights, so this ROTATES conviction and cannot scale the book:
        // every source keeps a strictly positive weight, so the ADR-0076 breadth count is unchanged.
        assertEquals(before.size(), after.size());
        after.values().forEach(w -> assertTrue(w > 0, "no source is stood down by this change"));
    }
}
