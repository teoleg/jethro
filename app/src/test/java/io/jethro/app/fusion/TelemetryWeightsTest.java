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

    // ---------------------------------------------------------------------------------------------
    // ADR-0087 — the lower bound is zero by default, so the worst-measured source is weighted by its
    // own evidence rather than held up off a floor.
    // ---------------------------------------------------------------------------------------------

    /** The cohort-aware shape (ADR-0077): the standard error is stdCohortMean/√cohorts, not σ/√n. */
    private static SignalScoring.Stats cohortStat(String source, long resolved, double avgBps,
                                                  long cohorts, double stdCohortMeanBps) {
        return new SignalScoring.Stats(source, resolved, 0, 0, resolved, 0, 0.0, avgBps,
                stdCohortMeanBps, cohorts, stdCohortMeanBps, 900L);
    }

    /**
     * The live 900s rung, verified against the desk's own published weight vector. Under the old 0.25
     * floor the whole vector below reproduces the live reading exactly EXCEPT `trend`, which the floor
     * lifts from its evidence-implied 0.088017996 to 0.25 — 2.84× the weight its own measurement
     * supports, on the richest sample and the most adverse reading on the desk (t = −3.158 over 230
     * resolved calls in 10 cohorts). Nothing else on the desk is anywhere near the floor, so the floor's
     * entire live effect was to over-trust the single worst source.
     *
     * <p>Worked by hand (Φ per A&amp;S 26.2.17, K = 20):
     * <pre>
     *   trend      SE = 4.617084/√10 = 1.460050  t = −3.158  Φ = 0.000794129  c = 230/250 = 0.92
     *   reversion  SE = 4.905532/√10 = 1.551268  t = +5.069  Φ = 0.999999799  c = 0.92
     *   pool = 0.508622647   meanShrunk = 0.470590252
     *   shrunk(trend) = 0.92·0.000794129 + 0.08·0.508622647 = 0.041420411 → /mean = 0.088017996
     * </pre>
     */
    @Test
    void theWorstMeasuredSourceIsWeightedByItsEvidenceNotByAFloor() {
        var live = List.of(
                cohortStat("mean-reversion", 2, 14.2835955, 2, 33.484134405088696),
                cohortStat("momentum", 35, -9.37321872115385, 26, 20.83912032547255),
                cohortStat("reversion", 230, 7.862724643478262, 10, 4.905532149880107),
                cohortStat("social", 14, 2.8117370000000004, 8, 9.268239354516679),
                cohortStat("trend", 230, -4.610953734782608, 10, 4.617084392531466));

        var floored = TelemetryWeights.compute(live, new TelemetryWeights.Params(20, 0.25, 3.0));
        assertEquals(0.25, floored.get("trend"), 1e-9, "the old floor binds on trend and nothing else");

        var unfloored = TelemetryWeights.compute(live, new TelemetryWeights.Params(20, 0, 3.0));
        assertEquals(0.088017996, unfloored.get("trend"), 1e-9);
        // Every other source is untouched — the floor was never their binding constraint.
        assertEquals(1.122973224, unfloored.get("mean-reversion"), 1e-9);
        assertEquals(0.407778135, unfloored.get("momentum"), 1e-9);
        assertEquals(2.041456708, unfloored.get("reversion"), 1e-9);
        assertEquals(1.339773937, unfloored.get("social"), 1e-9);
        for (String s : List.of("mean-reversion", "momentum", "reversion", "social")) {
            assertEquals(floored.get(s), unfloored.get(s), 1e-12, s + " is unaffected by the floor");
        }
    }

    /**
     * The safety property the floor was defending, shown to survive without it: a source at its
     * evidence-implied weight still CONTRIBUTES (the combiner admits any weight &gt; 0), so the active
     * source count — and therefore the ADR-0076 diversification multiplier's inputs — is unchanged.
     *
     * <p>The live SAP cross-section, worked by hand at ρ = 0.5: reversion +19.709008370022648 and trend
     * −20.0 (at the cap, opposing it, as they are on every planned name).
     * <pre>
     *   floored    avg = (19.709008·2.041457 − 20·0.25)/2.291457 = 15.376719630303986
     *              Σŵ² = 0.805590…  DM = 1.0524554443227607   → 16.183312290738098   (= the live book)
     *   unfloored  avg = (19.709008·2.041457 − 20·0.088018)/2.129475 = 18.067708137860887
     *              Σŵ² = 0.920815…  DM = 1.0204213186743043   → 18.436674563458464
     * </pre>
     * Removing the floor raises this name's conviction by 13.93% — the drag of a source measured to
     * LOSE money, taken off the only source measured to make it — and the DM falls, because the weights
     * are now more concentrated and ADR-0076 prices that honestly.
     */
    @Test
    void aSourceAtItsEvidenceWeightStillContributes() {
        var live = List.of(
                cohortStat("mean-reversion", 2, 14.2835955, 2, 33.484134405088696),
                cohortStat("momentum", 35, -9.37321872115385, 26, 20.83912032547255),
                cohortStat("reversion", 230, 7.862724643478262, 10, 4.905532149880107),
                cohortStat("social", 14, 2.8117370000000004, 8, 9.268239354516679),
                cohortStat("trend", 230, -4.610953734782608, 10, 4.617084392531466));
        var withFloor = TelemetryWeights.compute(live, new TelemetryWeights.Params(20, 0.25, 3.0));
        var noFloor = TelemetryWeights.compute(live, new TelemetryWeights.Params(20, 0, 3.0));

        var reversion = Forecast.of("reversion", "SAP", 19.709008370022648);
        var trend = Forecast.of("trend", "SAP", -20.0);

        var floored = ForecastCombiner.combine("SAP", List.of(
                new ForecastCombiner.Weighted(reversion, withFloor.get("reversion")),
                new ForecastCombiner.Weighted(trend, withFloor.get("trend"))), 0.5);
        var unfloored = ForecastCombiner.combine("SAP", List.of(
                new ForecastCombiner.Weighted(reversion, noFloor.get("reversion")),
                new ForecastCombiner.Weighted(trend, noFloor.get("trend"))), 0.5);

        assertEquals(2, unfloored.activeSources(), "no source is silenced by losing the floor");
        assertEquals(floored.activeSources(), unfloored.activeSources());
        assertEquals(16.183312290738098, floored.value(), 1e-9);
        assertEquals(18.436674563458464, unfloored.value(), 1e-9);
        assertTrue(unfloored.diversificationMultiplier() < floored.diversificationMultiplier(),
                "more concentrated weights earn LESS diversification scale-up, not more");
    }

    /** A non-positive min is the absence of a floor, not an instruction to install the old one. */
    @Test
    void aNonPositiveMinMeansNoFloor() {
        assertEquals(0.0, new TelemetryWeights.Params(20, 0, 3.0).min(), 1e-12);
        assertEquals(0.0, new TelemetryWeights.Params(20, -1, 3.0).min(), 1e-12);
        assertEquals(0.25, new TelemetryWeights.Params(20, 0.25, 3.0).min(), 1e-12);
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
