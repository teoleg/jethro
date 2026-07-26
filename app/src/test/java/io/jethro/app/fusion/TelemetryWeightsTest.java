package io.jethro.app.fusion;

import io.jethro.app.signal.SignalScoring;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0067 expectancy-weighted source trust: evidence in (per-source measured mean return, dispersion,
 * decisive sample), weight out — Φ of the sample t-statistic, shrunk toward the pooled prior by
 * credibility and bounded. Worked examples verified by hand (delta on the dimensionless ratios; the
 * money boundary is downstream in scaled decimals).
 */
class TelemetryWeightsTest {

    private static final TelemetryWeights.Params K20 = new TelemetryWeights.Params(20, 0.25, 3.0);

    /** A source's measured record: mean return per observation and its dispersion, both in bps. */
    private static SignalScoring.Stats stat(String source, double avgBps, double stdBps,
                                            long wins, long losses) {
        long resolved = wins + losses;
        double hitRate = resolved > 0 ? (double) wins / resolved : 0.0;
        return new SignalScoring.Stats(source, resolved, wins, losses, 0, 0, hitRate, avgBps, stdBps);
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
    void aThinSourceStaysNeutralUntilItEarnsTheSample() {
        // A spectacular reading on 3 decisive calls is luck, not evidence: the min-sample floor holds it
        // at neutral 1.0 while a well-sampled source is allowed to differentiate.
        var w = TelemetryWeights.compute(List.of(
                stat("social", 80.0, 20.0, 2, 1),        // 3 decisive < 20 → neutral
                stat("momentum", 10.0, 50.0, 60, 40)), K20);
        assertEquals(1.0, w.get("social"), 1e-9, "thin lucky source must not up-weight");
        assertTrue(w.get("momentum") != 1.0, "a well-sampled source still differentiates");
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
    void reWeightingRotatesConvictionButCannotScaleTheBook() {
        // The safety property that makes this change exposure-neutral by construction: the combiner
        // normalises by Σweights, so two sources saying the same thing produce the SAME combined
        // forecast — and therefore the same target position — whatever their relative weights are.
        var f = Forecast.of("a", "AAPL", 10.0);
        var g = Forecast.of("b", "AAPL", 10.0);
        var equal = ForecastCombiner.combine("AAPL", List.of(
                new ForecastCombiner.Weighted(f, 1.0), new ForecastCombiner.Weighted(g, 1.0)), 0.5);
        var skewed = ForecastCombiner.combine("AAPL", List.of(
                new ForecastCombiner.Weighted(f, 0.25), new ForecastCombiner.Weighted(g, 3.0)), 0.5);
        assertEquals(equal.value(), skewed.value(), 1e-12);
        assertEquals(equal.activeSources(), skewed.activeSources());
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
