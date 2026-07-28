package io.jethro.app.fusion;

import io.jethro.app.signal.SignalScoring;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0067 expectancy-weighted source trust, on the ADR-0074 sample: evidence in (per-source measured
 * mean return, dispersion, resolved sample), weight out — Φ of the sample t-statistic, shrunk toward
 * the pooled prior by credibility over the SAME resolved observations the statistic averaged, and
 * bounded. Worked examples verified by hand (delta on the dimensionless ratios; the money boundary is
 * downstream in scaled decimals).
 */
class TelemetryWeightsTest {

    private static final TelemetryWeights.Params K20 = new TelemetryWeights.Params(20, 0.25, 3.0);

    /** A source's measured record: mean return per observation and its dispersion, both in bps. */
    private static SignalScoring.Stats stat(String source, double avgBps, double stdBps,
                                            long wins, long losses) {
        return stat(source, avgBps, stdBps, wins, losses, 0);
    }

    /** As above, with FLAT observations — calls that resolved inside the dead-band, earning nothing. */
    private static SignalScoring.Stats stat(String source, double avgBps, double stdBps,
                                            long wins, long losses, long flats) {
        long resolved = wins + losses + flats;
        long decisive = wins + losses;
        double hitRate = decisive > 0 ? (double) wins / decisive : 0.0;
        return new SignalScoring.Stats(source, resolved, wins, losses, flats, 0, hitRate, avgBps, stdBps);
    }

    @Test
    void coldStartWeightsEveryoneEqually() {
        // No resolved observations ⇒ no standard error ⇒ t=0 ⇒ Φ(0)=½ for all ⇒ equal by symmetry.
        var w = TelemetryWeights.compute(List.of(
                stat("momentum", 0.0, 0.0, 0, 0),
                stat("hypothesis", 0.0, 0.0, 0, 0),
                stat("social", 0.0, 0.0, 0, 0)), K20);
        assertEquals(1.0, w.get("momentum"), 1e-9);
        assertEquals(1.0, w.get("hypothesis"), 1e-9);
        assertEquals(1.0, w.get("social"), 1e-9);
    }

    @Test
    void aWholeDeskBelowCoinFlipStillDiscriminates() {
        // THE REGRESSION this change exists for. Under the old max(0, 2·hitRate−1) statistic every
        // below-coin-flip source floored to advantage 0, the pooled mean went to 0, and the method fell
        // back to weighting a measured loser exactly like everyone else — observed live with three
        // sources at hit rates 0.32/0.33/0.00 all carrying weight 1.0. Expectancy has no flat region.
        //
        // A: mean −20 bps, σ 40, n 25 → SE 8   → t −2.5   → Φ 0.00620967
        // B: mean  −2 bps, σ 40, n 25 → SE 8   → t −0.25  → Φ 0.40129367
        // c = 25/(25+20) = 0.555556 for both;  pool = 0.20375167
        // shrunkA = 0.555556·0.00620967 + 0.444444·0.20375167 = 0.09400612
        // shrunkB = 0.555556·0.40129367 + 0.444444·0.20375167 = 0.31349723   mean = 0.20375167
        // wA = 0.461381,  wB = 1.538619   (they sum to n=2, as ratios to their own mean must)
        var w = TelemetryWeights.compute(List.of(
                stat("A", -20.0, 40.0, 10, 15),
                stat("B", -2.0, 40.0, 10, 15)), K20);
        assertEquals(0.461381, w.get("A"), 1e-5);
        assertEquals(1.538619, w.get("B"), 1e-5);
        assertTrue(w.get("A") < w.get("B"), "the worse-measured source must carry less conviction");
    }

    @Test
    void aMeasuredLoserIsDownWeightedNeverInverted() {
        // Strongly negative expectancy drives Φ→0, so the weight falls to the MIN floor — down-weighted
        // to a whisper, still contributing, never flipped into a contrarian bet on its own failure.
        var w = TelemetryWeights.compute(List.of(
                stat("loser", -100.0, 20.0, 20, 40),   // t = −100/(20/√60) ≈ −38.7 → Φ ≈ 0
                stat("neutral", 0.0, 20.0, 30, 30)), K20);
        assertEquals(0.25, w.get("loser"), 1e-9);
        assertTrue(w.get("loser") > 0, "a decayed source stays in the combine, down-weighted not dropped");
        assertTrue(w.get("neutral") > w.get("loser"));
    }

    @Test
    void anAsymmetricPayoffSourceIsJudgedOnMoneyNotOnHitRate() {
        // The reason for the change of statistic: a trend follower is DESIGNED to be right under half
        // the time and paid by asymmetry. Judged on hit rate it looks like the worse source; judged on
        // expectancy — the money it actually makes per call — it is plainly the better one.
        // trend:   hit 0.30, mean +30 bps, σ 100, n 50 → t +2.1213 → Φ 0.98305
        // chopper: hit 0.60, mean  −5 bps, σ 100, n 50 → t −0.3536 → Φ 0.36183
        var w = TelemetryWeights.compute(List.of(
                stat("trend", 30.0, 100.0, 15, 35),
                stat("chopper", -5.0, 100.0, 30, 20)), K20);
        assertTrue(w.get("trend") > 1.0, "positive measured expectancy earns above-neutral conviction");
        assertTrue(w.get("chopper") < 1.0, "negative measured expectancy loses conviction");
        assertEquals(1.329975, w.get("trend"), 1e-4);
        assertEquals(0.670025, w.get("chopper"), 1e-4);
    }

    @Test
    void aThinSourceStaysEssentiallyNeutralUntilItEarnsTheSample() {
        // ADR-0074: credibility alone, no hard floor. A spectacular reading on 3 calls is luck, not
        // evidence — c = 3/(3+20) = 0.130435, so the source sits ~87% on the pooled prior and lands a
        // whisker off neutral rather than being pinned there by a branch.
        // social:   mean +80 bps, σ 20, n 3   → SE 11.547005 → t +6.9282 → Φ 1.0        c 0.130435
        // momentum: mean +10 bps, σ 50, n 100 → SE  5.0      → t +2.0    → Φ 0.97724994 c 0.833333
        // pool = 0.98862497
        // shrunk(social) = 0.130435·1.0 + 0.869565·0.98862497 = 0.99010867
        // shrunk(momentum) = 0.833333·0.97724994 + 0.166667·0.98862497 = 0.97914578
        // mean = 0.98462722 → w(social) = 1.005567, w(momentum) = 0.994433
        var w = TelemetryWeights.compute(List.of(
                stat("social", 80.0, 20.0, 2, 1),
                stat("momentum", 10.0, 50.0, 60, 40)), K20);
        assertEquals(1.005567, w.get("social"), 1e-5, "thin lucky source must not meaningfully up-weight");
        assertTrue(Math.abs(w.get("social") - 1.0) < 0.02, "three calls buy almost no conviction");
        assertTrue(w.get("momentum") != 1.0, "a well-sampled source still differentiates");
    }

    @Test
    void flatOutcomesCountAsEvidenceJustAsTheExpectancyCountsThem() {
        // THE REGRESSION this change exists for (ADR-0074). Both sources resolved 40 calls with the
        // SAME measured expectancy and the SAME dispersion — byte-identical evidence — and differ only
        // in how their outcomes bucketed: one landed 30 of 40 inside the flat dead-band, the other none.
        // avgReturnBps and stdErrorBps average over ALL resolved calls, so the evidence is identical;
        // the old credibility term counted wins+losses only, so "flat-heavy" had 10 decisive against
        // "decisive"'s 40 and fell under the old min-sample floor — pinned at a MORE trusting 1.0 while
        // its twin was down-weighted to 0.310741. A 3.2x conviction gap on identical measurements.
        //
        // both: mean −12 bps, σ 30, n 40 → SE 4.743416 → t −2.529822 → Φ 0.00570604, c = 40/60 = 2/3
        // reference: mean +12 bps, σ 30, n 40 → t +2.529822 → Φ 0.99429396, c = 2/3
        // pool = 0.33523535
        // shrunk(flat-heavy) = shrunk(decisive) = ⅔·0.00570604 + ⅓·0.33523535 = 0.11554914
        // shrunk(reference)  = ⅔·0.99429396 + ⅓·0.33523535 = 0.77460776   mean = 0.33523535
        // w = 0.3446807 / 0.3446807 / 2.3106387
        var w = TelemetryWeights.compute(List.of(
                stat("flat-heavy", -12.0, 30.0, 3, 7, 30),
                stat("decisive", -12.0, 30.0, 12, 28, 0),
                stat("reference", 12.0, 30.0, 20, 20, 0)), K20);
        assertEquals(w.get("decisive"), w.get("flat-heavy"), 1e-12,
                "identical evidence over an identical sample must buy identical conviction");
        assertEquals(0.3446807, w.get("flat-heavy"), 1e-6);
        assertEquals(0.3446807, w.get("decisive"), 1e-6);
        assertEquals(2.3106387, w.get("reference"), 1e-6);
    }

    @Test
    void aFlatHeavyLoserNoLongerReadsAsNoEvidence() {
        // The live shape this was found in: a source with 18 resolved calls averaging −11.3 bps, 11 of
        // them FLAT, sat at full trust 1.0 because only 7 were decisive — measured-negative evidence
        // read as absent. It must now carry LESS conviction than a source with no reading at all.
        var w = TelemetryWeights.compute(List.of(
                stat("momentum", -11.296798, 30.457460, 1, 6, 11),
                stat("unmeasured", 0.0, 0.0, 0, 0, 0),
                stat("reference", 0.0, 30.0, 20, 20, 0)), K20);
        assertTrue(w.get("momentum") < 1.0, "a measured loss must cost conviction, flats and all");
        assertTrue(w.get("momentum") < w.get("unmeasured"),
                "a source measured losing must be trusted less than one never measured");
    }

    @Test
    void weightsAreBoundedByMinAndMaxParams() {
        // Same inputs as the discrimination case (raw 0.461381 / 1.538619) under a tighter band.
        var tight = new TelemetryWeights.Params(20, 0.5, 1.5);
        var w = TelemetryWeights.compute(List.of(
                stat("A", -20.0, 40.0, 10, 15),
                stat("B", -2.0, 40.0, 10, 15)), tight);
        assertEquals(0.5, w.get("A"), 1e-9);
        assertEquals(1.5, w.get("B"), 1e-9);
    }

    @Test
    void singleSourceGetsTheNeutralWeight() {
        var w = TelemetryWeights.compute(List.of(stat("A", 25.0, 30.0, 60, 40)), K20);
        assertEquals(1.0, w.get("A"), 1e-9);
    }

    @Test
    void emptyTelemetryYieldsNoWeights() {
        assertTrue(TelemetryWeights.compute(List.of(), K20).isEmpty());
    }

    @Test
    void sourceWithoutTelemetryTakesTheNeutralDefault() {
        // the learned signal has its own walk-forward gate, no signal-telemetry entry → neutral 1.0
        var fw = FusionWeights.fromTelemetry(List.of(stat("momentum", 25.0, 30.0, 30, 10)), K20);
        assertEquals(1.0, fw.weightFor("learned"), 1e-9);
    }

    @Test
    void reWeightingRotatesConvictionAndCanOnlyShrinkTheBook() {
        // The safety property, strengthened by ADR-0076. The combiner still normalises by Σweights, so
        // the weighted AVERAGE of two sources saying the same thing is identical however they are
        // weighted — re-weighting rotates conviction. What re-weighting also does now is set the
        // diversification multiplier from the breadth the weights deliver, so a skewed vector earns
        // LESS scale-up than an equal one. Direction is one-way: never larger than equal weights.
        var f = Forecast.of("a", "AAPL", 10.0);
        var g = Forecast.of("b", "AAPL", 10.0);
        var equal = ForecastCombiner.combine("AAPL", List.of(
                new ForecastCombiner.Weighted(f, 1.0), new ForecastCombiner.Weighted(g, 1.0)), 0.5);
        var skewed = ForecastCombiner.combine("AAPL", List.of(
                new ForecastCombiner.Weighted(f, 0.25), new ForecastCombiner.Weighted(g, 3.0)), 0.5);
        assertEquals(equal.activeSources(), skewed.activeSources(), "no source is silenced");
        assertTrue(skewed.value() < equal.value(), "a 12:1 weight split is nearly one view, not two");
        assertTrue(skewed.value() >= 10.0, "…and never below the un-diversified forecast itself");
        // Equal weights reproduce the pre-ADR-0076 count rule exactly, so the null case is unchanged.
        assertEquals(10.0 / Math.sqrt(0.75), equal.value(), 1e-12);
    }

    // ---------------------------------------------------------------------------------------------
    // ADR-0097: a source that has not demonstrated a directional edge is held at the MIN weight.
    // ---------------------------------------------------------------------------------------------

    /** The gate's own parameters: 30 resolved observations minimum, α = 1 − Φ(2.0) = 0.0227501…. */
    private static final EdgeGate.Params GATE = new EdgeGate.Params(30, 2.0);

    /** A cohort-aware record: the ADR-0077 standard error is stdCohortMeanBps / √cohorts. */
    private static SignalScoring.Stats cohortStat(String source, long resolved, long cohorts,
                                                  double avgBps, double stdCohortMeanBps) {
        return new SignalScoring.Stats(source, resolved, resolved / 2, 0, resolved - resolved / 2, 0,
                1.0, avgBps, stdCohortMeanBps, cohorts, stdCohortMeanBps);
    }

    /**
     * The live shape that motivated the rule, restated on round numbers.
     *
     * <p>STRONG: 500 resolved over 39 cohorts, +10.0 bps, cohort sd 6.0 bps.
     * SE = 6.0/√39 = 0.960769…; t = 10.0/0.960769… = 10.4083…; on 38 df the upper tail is ~4e-13,
     * far inside α = 0.02275 ⇒ ADMITTED.
     *
     * <p>WEAK: 51 resolved over 31 cohorts, +3.25 bps, cohort sd 10.7 bps.
     * SE = 10.7/√31 = 1.921797…; t = 3.25/1.921797… = 1.69112…; on 30 df the upper tail is ≈ 0.0505,
     * i.e. outside α ⇒ NOT admitted. Φ(1.69) = 0.9545 against Φ(10.4) = 1.0000, so on evidence alone
     * WEAK carried ~95% of STRONG's trust — a source failing the desk's own test all but tying with
     * the only one passing it. That is the saturation this rule closes.
     */
    @Test
    void aSourceThatCannotDemonstrateAnEdgeIsHeldAtTheMinimumWeight() {
        var stats = List.of(
                cohortStat("strong", 500, 39, 10.0, 6.0),
                cohortStat("weak", 51, 31, 3.25, 10.7));

        assertTrue(EdgeGate.demonstratesEdge(stats.get(0), GATE), "t = 10.41 on 38 df clears α");
        assertTrue(!EdgeGate.demonstratesEdge(stats.get(1), GATE), "t = 1.69 on 30 df does not");

        var before = TelemetryWeights.compute(stats, K20);
        var after = TelemetryWeights.compute(stats, K20, GATE);

        assertTrue(before.get("weak") > 0.9 * before.get("strong"),
                "without the rule the failing source nearly ties the passing one: "
                        + before.get("weak") + " vs " + before.get("strong"));
        assertEquals(0.25, after.get("weak"), 1e-12, "demoted to exactly Params.min()");
        assertEquals(before.get("strong"), after.get("strong"), 1e-12, "the admitted source is untouched");
    }

    @Test
    void theRuleIsOneWayAndNeverRaisesAWeight() {
        var stats = List.of(
                cohortStat("strong", 500, 39, 10.0, 6.0),
                cohortStat("weak", 51, 31, 3.25, 10.7),
                cohortStat("bad", 500, 23, -6.7, 6.26));
        var before = TelemetryWeights.compute(stats, K20);
        var after = TelemetryWeights.compute(stats, K20, GATE);
        for (var e : before.entrySet()) {
            assertTrue(after.get(e.getKey()) <= e.getValue() + 1e-12,
                    e.getKey() + ": a demotion can only ever lower a weight");
            assertTrue(after.get(e.getKey()) >= 0.0, e.getKey() + ": never inverted into a contrarian bet");
        }
        // ADR-0111: "bad" reads −6.7 bps on 23 cohorts, cohort sd 6.26 ⇒ SE = 6.26/√23 = 1.30528…,
        // t = −5.1329… — significantly LOSING on 22 df, not merely unproven. It is stood down, not
        // floored; the weight is 0, never negative.
        assertEquals(0.0, after.get("bad"), 1e-12, "a measured-LOSING source leaves the vote (ADR-0111)");
        assertEquals(0.25, after.get("weak"), 1e-12, "an UNPROVEN source is still held at MIN (ADR-0097)");
    }

    // ---------------------------------------------------------------------------------------------
    // ADR-0111: unproven and disconfirmed are different findings and get different treatment.
    // ---------------------------------------------------------------------------------------------

    @Test
    void theThreeStatesAreTotalAndMutuallyExclusive() {
        var strong = cohortStat("strong", 500, 39, 10.0, 6.0);   // t = +10.41 on 38 df
        var weak = cohortStat("weak", 51, 31, 3.25, 10.7);       // t = +1.69 on 30 df
        var bad = cohortStat("bad", 500, 23, -6.7, 6.26);        // t = −5.13 on 22 df
        assertTrue(EdgeGate.demonstratesEdge(strong, GATE) && !EdgeGate.contradictsEdge(strong, GATE),
                "DEMONSTRATED");
        assertTrue(!EdgeGate.demonstratesEdge(weak, GATE) && !EdgeGate.contradictsEdge(weak, GATE),
                "UNPROVEN — the sample cannot tell either way");
        assertTrue(!EdgeGate.demonstratesEdge(bad, GATE) && EdgeGate.contradictsEdge(bad, GATE),
                "CONTRADICTED");
        // Mutually exclusive for any α < ½: a mean cannot be significantly above AND below zero. The
        // mirrored test is the same statistic on the same sample, so this holds by construction.
        for (double avg : new double[] {-40.0, -6.7, -0.01, 0.0, 0.01, 3.25, 40.0}) {
            var s = cohortStat("s", 500, 39, avg, 6.0);
            assertTrue(!(EdgeGate.demonstratesEdge(s, GATE) && EdgeGate.contradictsEdge(s, GATE)),
                    "no source is both admitted and contradicted at avg " + avg);
        }
    }

    @Test
    void standingDownADisconfirmedSourceRestoresTheConvictionItWasSubtracting() {
        // The live shape at the 225 s rung that motivated the rule, on the desk's own readings:
        //   reversion  +2.0087 bps, SE 0.4271 ⇒ t = +4.70  ⇒ DEMONSTRATED, weight 2.899…
        //   trend      −1.5033 bps, SE 0.3860 ⇒ t = −3.89  ⇒ CONTRADICTED, weight 0.25 (was)
        // and GOOG's two fresh forecasts that cycle: reversion +15.134, trend −13.974.
        //
        // Held at MIN, by hand:
        //   average = (15.134·2.899 + (−13.974)·0.25) / (2.899 + 0.25) = 40.379966/3.149 = 12.823139…
        //   Σw²ₙ    = (2.899² + 0.25²)/3.149² = 8.466701/9.916201 = 0.8538178…
        //   DM      = 1/√(0.8538178… + 0.5·(1 − 0.8538178…)) = 1/√0.9269089… = 1.0386636…
        //   agreement = |40.379966| / (15.134·2.899 + 13.974·0.25) = 40.379966/47.366966 = 0.8524921…
        //   combined = 12.823139… × 1.0386636… × 0.8524921… = 11.354402…      (ADR-0119)
        // Stood down, by hand: one active source ⇒ average = 15.134, Σw²ₙ = 1, DM = 1, agreement 1
        // (nothing left to contradict it), combined = 15.134.
        // The disconfirmed source was costing the desk conviction on this name twice over: once through
        // the average it dragged down, and again through the agreement it destroyed.
        var reversion = Forecast.of("reversion", "GOOG", 15.134);
        var trend = Forecast.of("trend", "GOOG", -13.974);

        var floored = ForecastCombiner.combine("GOOG", List.of(
                new ForecastCombiner.Weighted(reversion, 2.899),
                new ForecastCombiner.Weighted(trend, 0.25)), 0.5);
        var stoodDown = ForecastCombiner.combine("GOOG", List.of(
                new ForecastCombiner.Weighted(reversion, 2.899),
                new ForecastCombiner.Weighted(trend, 0.0)), 0.5);

        assertEquals(0.8524921355528662, floored.agreement(), 1e-12);
        assertEquals(11.354402, floored.value(), 1e-6, "the shipped behaviour, by hand");
        assertEquals(2, floored.activeSources());
        assertEquals(15.134, stoodDown.value(), 1e-9, "one view, no diversification claimed");
        assertEquals(1, stoodDown.activeSources(), "a stood-down source is not breadth");
        assertEquals(1.0, stoodDown.diversificationMultiplier(), 1e-12);
        assertEquals(1.0, stoodDown.agreement(), 1e-12, "one view cannot disagree with itself");
        assertTrue(stoodDown.value() > floored.value(),
                "conviction the desk had measured is no longer surrendered to a measured loser");
    }

    @Test
    void someWeightAlwaysSurvivesTheStandDown() {
        // A stand-down happens only when some source IS admitted, and no source is both — so the
        // weight vector can never be zeroed out from under the combiner.
        var stats = List.of(
                cohortStat("strong", 500, 39, 10.0, 6.0),
                cohortStat("bad", 500, 23, -6.7, 6.26),
                cohortStat("worse", 500, 39, -10.0, 6.0));
        var after = TelemetryWeights.compute(stats, K20, GATE);
        assertTrue(after.values().stream().anyMatch(w -> w > 0), "at least one source still votes");
        assertEquals(0.0, after.get("bad"), 1e-12);
        assertEquals(0.0, after.get("worse"), 1e-12);
        assertTrue(after.get("strong") > 0);
    }

    @Test
    void withNoSourceAdmittedTheWeightsAreLeftExactlyAsMeasured() {
        // Nothing clears at zero cost ⇒ nothing can clear the gate at a non-negative cost either, so
        // the desk is reduce-only; flattening the vector here would change the diversification
        // multiplier for no gain. Byte-identical to the pre-ADR-0097 weights.
        var stats = List.of(
                cohortStat("weak", 51, 31, 3.25, 10.7),
                cohortStat("bad", 500, 23, -6.7, 6.26));
        assertEquals(TelemetryWeights.compute(stats, K20), TelemetryWeights.compute(stats, K20, GATE));
    }

    @Test
    void nullAdmissionIsThePreviousBehaviourExactly() {
        var stats = List.of(
                cohortStat("strong", 500, 39, 10.0, 6.0),
                cohortStat("weak", 51, 31, 3.25, 10.7));
        assertEquals(TelemetryWeights.compute(stats, K20), TelemetryWeights.compute(stats, K20, null));
    }

    @Test
    void theAdmissionTestIsTheGatesOwnTestAtZeroCost() {
        var weak = cohortStat("weak", 51, 31, 3.25, 10.7);
        var strong = cohortStat("strong", 500, 39, 10.0, 6.0);
        // Monotone in cost: whatever clears at a positive round trip clears at zero, so the admitted
        // set can never be smaller than the set the gate itself passes.
        for (double cost : new double[] {0.0, 1.56, 5.0, 9.0}) {
            assertTrue(!EdgeGate.clears(strong.resolved(), strong.cohorts(), strong.avgReturnBps(),
                            strong.stdErrorBps(), cost, GATE)
                            || EdgeGate.demonstratesEdge(strong, GATE),
                    "clearing at cost " + cost + " implies clearing at zero");
            assertTrue(!EdgeGate.clears(weak.resolved(), weak.cohorts(), weak.avgReturnBps(),
                            weak.stdErrorBps(), cost, GATE)
                            || EdgeGate.demonstratesEdge(weak, GATE),
                    "clearing at cost " + cost + " implies clearing at zero");
        }
        // A sample below the gate's minimum is no evidence, whatever it reads.
        assertTrue(!EdgeGate.demonstratesEdge(cohortStat("thin", 10, 9, 40.0, 1.0), GATE));
        // A single cohort supports no standard error at all (ADR-0077) ⇒ no admission.
        assertTrue(!EdgeGate.demonstratesEdge(cohortStat("onedraw", 500, 1, 40.0, 1.0), GATE));
    }

    @Test
    void normalCdfMatchesPublishedValues() {
        assertEquals(0.5, TelemetryWeights.standardNormalCdf(0.0), 1e-9);
        assertEquals(0.8413447, TelemetryWeights.standardNormalCdf(1.0), 1e-6);
        assertEquals(0.0249979, TelemetryWeights.standardNormalCdf(-1.96), 1e-6);
        assertEquals(0.9937903, TelemetryWeights.standardNormalCdf(2.5), 1e-6);
        // symmetry: Φ(x) + Φ(−x) = 1
        assertEquals(1.0, TelemetryWeights.standardNormalCdf(0.7)
                + TelemetryWeights.standardNormalCdf(-0.7), 1e-9);
    }
}
