package io.jethro.app.fusion;

import io.jethro.app.signal.SignalScoring;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0093 edge-weighted source trust, on the ADR-0067 statistic and the ADR-0074 sample: evidence in
 * (per-source measured mean return, resolved sample), weight out — the source's own measured
 * expectancy, shrunk toward the LEAVE-ONE-OUT average of the rest of the desk by credibility over the
 * SAME resolved observations the statistic averaged, floored at zero and bounded. Worked examples
 * verified by hand below each case (the arithmetic is on dimensionless ratios; the money boundary is
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
        // No resolved observations ⇒ no measured edge anywhere ⇒ nothing to form a ratio from.
        var w = TelemetryWeights.compute(List.of(
                stat("momentum", 0.0, 0.0, 0, 0),
                stat("hypothesis", 0.0, 0.0, 0, 0),
                stat("social", 0.0, 0.0, 0, 0)), K20);
        assertEquals(1.0, w.get("momentum"), 1e-9);
        assertEquals(1.0, w.get("hypothesis"), 1e-9);
        assertEquals(1.0, w.get("social"), 1e-9);
    }

    @Test
    void aWellSampledNullEarnsTheFloorNotAFullUnitOfWeight() {
        // THE REGRESSION this change exists for (ADR-0093). Under Φ(t) the null sat at exactly ½, and
        // the normaliser turned ½ into a full unit of weight: a source measuring +0.28 bps against a
        // ±8.0 bps standard error — literally no information — carried 49% of the weight of a source
        // measured at t = +9.0 over 483 resolved calls. A source that has shown nothing is worth
        // nothing. Weight is proportional to the EDGE, and this source's edge is zero.
        //
        // edge:  µ̂ = +10 bps, n = 180 → c = 180/200 = 0.9;  pool₋ᵢ = 0   → ê = 0.9·10 + 0.1·0  = 9.0
        // noise: µ̂ =   0 bps, n = 180 → c = 0.9;            pool₋ᵢ = 10  → ê = 0.9·0  + 0.1·10 = 1.0
        // mean = 5.0 → w(edge) = 1.8, w(noise) = 0.2 → floored at MIN 0.25
        var w = TelemetryWeights.compute(List.of(
                stat("edge", 10.0, 40.0, 90, 90),
                stat("noise", 0.0, 40.0, 90, 90)), K20);
        assertEquals(1.8, w.get("edge"), 1e-9);
        assertEquals(0.25, w.get("noise"), 1e-9);
        assertTrue(w.get("noise") < 1.0, "a source that has shown nothing must not read as neutral");
    }

    @Test
    void weightIsProportionalToTheEdgeAndDoesNotSaturate() {
        // The second Φ defect: confidence saturates and edge does not — Φ(2.0) = 0.977 against
        // Φ(9.4) = 1.000, so an overwhelming edge bought at most 2.3% more weight than one barely
        // clearing a 2-sigma hurdle. Here twice the edge buys (very nearly) twice the weight, which is
        // the whole point: discrimination survives at the top of the scale, where it pays.
        //
        // all three: n = 400 → c = 400/420 = 20/21;  Σµ̂ = +6
        // strong: µ̂ = +12, pool₋ᵢ = (6−12)/2 = −3 → ê = (20/21)·12 + (1/21)·(−3) = 79/7  = 11.285714
        // mild:   µ̂ =  +6, pool₋ᵢ = (6−6)/2  =  0 → ê = (20/21)·6                = 40/7  =  5.714286
        // loser:  µ̂ = −12, pool₋ᵢ = (6+12)/2 =  9 → ê = (20/21)·(−12) + (1/21)·9 = −11.0 → 0
        // mean = (79/7 + 40/7 + 0)/3 = 17/3 → w = 237/119, 120/119, floor
        var w = TelemetryWeights.compute(List.of(
                stat("strong", 12.0, 100.0, 200, 200),
                stat("mild", 6.0, 100.0, 200, 200),
                stat("loser", -12.0, 100.0, 200, 200)), K20);
        assertEquals(1.9915966386554622, w.get("strong"), 1e-12);
        assertEquals(1.0084033613445378, w.get("mild"), 1e-12);
        assertEquals(0.25, w.get("loser"), 1e-12);
        // Twice the measured edge is worth 237/120 = 1.975× the conviction — not exactly 2, because
        // shrinkage toward each one's leave-one-out prior pulls them together a little. Under Φ the
        // same pair (t = +2.4 vs +1.2 on this sample) would have differed by about 12%.
        assertEquals(1.975, w.get("strong") / w.get("mild"), 1e-12);
    }

    @Test
    void theWeightIsFreeOfTheUnitTheEdgeIsMeasuredIn() {
        // Homogeneous of degree zero in µ̂: multiply every source's measured expectancy by any k > 0
        // and every ê, the mean and therefore every weight is unchanged. So the unit the telemetry
        // happens to report in — bps, percent, raw return — cannot move a weight.
        var bps = TelemetryWeights.compute(List.of(
                stat("strong", 12.0, 100.0, 200, 200),
                stat("mild", 6.0, 100.0, 200, 200),
                stat("loser", -12.0, 100.0, 200, 200)), K20);
        var scaled = TelemetryWeights.compute(List.of(
                stat("strong", 12000.0, 100000.0, 200, 200),
                stat("mild", 6000.0, 100000.0, 200, 200),
                stat("loser", -12000.0, 100000.0, 200, 200)), K20);
        assertEquals(bps.keySet(), scaled.keySet());
        // Equal to floating-point rounding: the two paths differ only in the magnitude of the
        // intermediate sums, so the ratios agree to the last few ULPs rather than bit-for-bit.
        bps.forEach((src, w) -> assertEquals(w, scaled.get(src), 1e-12, src));
    }

    @Test
    void aMeasuredLoserIsDownWeightedNeverInverted() {
        // Strongly negative expectancy drives the shrunk edge below zero, so the positive part takes
        // it to the MIN floor — down-weighted to a whisper, still contributing, never flipped into a
        // contrarian bet on its own failure (Harvey, Liu & Zhu, RFS 2016).
        //
        // proven: µ̂ = +20, n = 200 → c = 10/11; pool₋ᵢ = −100 → ê = (10/11)·20 + (1/11)·(−100) = 100/11
        // loser:  µ̂ = −100, n = 60 → c = 0.75;  pool₋ᵢ = +20  → ê = 0.75·(−100) + 0.25·20 = −70 → 0
        // mean = 50/11 → w(proven) = 2.0, w(loser) = floor
        var w = TelemetryWeights.compute(List.of(
                stat("proven", 20.0, 40.0, 120, 80),
                stat("loser", -100.0, 20.0, 20, 40)), K20);
        assertEquals(2.0, w.get("proven"), 1e-12);
        assertEquals(0.25, w.get("loser"), 1e-12);
        assertTrue(w.get("loser") > 0, "a decayed source stays in the combine, down-weighted not dropped");
    }

    @Test
    void anAsymmetricPayoffSourceIsJudgedOnMoneyNotOnHitRate() {
        // ADR-0067's property, unchanged: a trend follower is DESIGNED to be right under half the time
        // and paid by asymmetry. Judged on hit rate it looks like the worse source; judged on the money
        // it actually makes per call it is plainly the better one.
        //
        // both: n = 50 → c = 5/7;  Σµ̂ = +25
        // trend   (hit 0.30): µ̂ = +30, pool₋ᵢ = −5 → ê = (5/7)·30 + (2/7)·(−5) = 20.0
        // chopper (hit 0.60): µ̂ =  −5, pool₋ᵢ = 30 → ê = (5/7)·(−5) + (2/7)·30 =  5.0
        // mean = 12.5 → 1.6 / 0.4
        var w = TelemetryWeights.compute(List.of(
                stat("trend", 30.0, 100.0, 15, 35),
                stat("chopper", -5.0, 100.0, 30, 20)), K20);
        assertEquals(1.6, w.get("trend"), 1e-12);
        assertEquals(0.4, w.get("chopper"), 1e-12);
        assertTrue(w.get("trend") > 1.0 && w.get("chopper") < 1.0);
    }

    @Test
    void aThinLuckySourceIsShrunkTowardTheDeskAndNotTowardItsOwnLuck() {
        // Why the prior is LEAVE-ONE-OUT (ADR-0093). On the bounded Φ scale a self-inclusive pool was
        // harmless because everything saturated; on the unbounded edge scale a thin source with an
        // extreme reading drags the pool toward itself and is then shrunk toward its own luck. With the
        // pool taken over the OTHER sources only, a spectacular reading on three calls lands BELOW
        // neutral — c = 3/23, so 87% of its weight comes from what the rest of the desk has measured.
        //
        // social:   µ̂ = +80, n =   3 → c = 3/23; pool₋ᵢ = +10 → ê = (3/23)·80 + (20/23)·10 = 440/23
        // momentum: µ̂ = +10, n = 100 → c = 5/6;  pool₋ᵢ = +80 → ê = (5/6)·10 + (1/6)·80    =  65/3
        // mean = 2815/138 → w(social) = 528/563, w(momentum) = 598/563
        var w = TelemetryWeights.compute(List.of(
                stat("social", 80.0, 20.0, 2, 1),
                stat("momentum", 10.0, 50.0, 60, 40)), K20);
        assertEquals(0.9378330373001776, w.get("social"), 1e-12);
        assertEquals(1.0621669626998225, w.get("momentum"), 1e-12);
        assertTrue(w.get("social") < 1.0, "three calls must not buy above-neutral conviction");
        assertTrue(w.get("social") < w.get("momentum"), "the well-sampled source carries the view");
    }

    @Test
    void flatOutcomesCountAsEvidenceJustAsTheExpectancyCountsThem() {
        // ADR-0074's invariant, which this change preserves exactly. Both sources resolved 40 calls
        // with the SAME measured expectancy — byte-identical evidence — and differ only in how their
        // outcomes bucketed: one landed 30 of 40 inside the flat dead-band, the other none. Since
        // avgReturnBps averages over ALL resolved calls, credibility must count them all too; the
        // pre-ADR-0074 term counted wins+losses only and pinned the flat-heavy one at a MORE trusting
        // 1.0 while its twin was down-weighted.
        //
        // all three: n = 40 → c = 2/3;  Σµ̂ = −12
        // flat-heavy / decisive: µ̂ = −12, pool₋ᵢ = 0   → ê = (2/3)·(−12) = −8 → 0
        // reference:             µ̂ = +12, pool₋ᵢ = −12 → ê = (2/3)·12 + (1/3)·(−12) = 4
        // mean = 4/3 → w(reference) = 3.0 (exactly at MAX), the twins floor together
        var w = TelemetryWeights.compute(List.of(
                stat("flat-heavy", -12.0, 30.0, 3, 7, 30),
                stat("decisive", -12.0, 30.0, 12, 28, 0),
                stat("reference", 12.0, 30.0, 20, 20, 0)), K20);
        assertEquals(w.get("decisive"), w.get("flat-heavy"), 1e-12,
                "identical evidence over an identical sample must buy identical conviction");
        assertEquals(0.25, w.get("flat-heavy"), 1e-12);
        assertEquals(3.0, w.get("reference"), 1e-12);
    }

    @Test
    void aFlatHeavyLoserFallsToTheFloorRatherThanReadingAsNoEvidence() {
        // The live shape ADR-0074 was found in: a source with 18 resolved calls averaging −11.3 bps,
        // 11 of them FLAT, sat at full trust 1.0 because only 7 were decisive — measured-negative
        // evidence read as absent. It now carries the floor.
        //
        // Σµ̂ = +0.703202
        // momentum:  µ̂ = −11.296798, n =  18 → c = 9/19;   pool₋ᵢ =  6.0      → ê = −2.19322 → 0
        // reference: µ̂ =        0.0, n =  40 → c = 2/3;    pool₋ᵢ =  0.351601 → ê =  0.11720
        // proven:    µ̂ =      +12.0, n = 400 → c = 20/21;  pool₋ᵢ = −5.648399 → ê = 11.15960
        // mean = 3.758933 → proven 2.968821, the other two below MIN and floored
        var w = TelemetryWeights.compute(List.of(
                stat("momentum", -11.296798, 30.457460, 1, 6, 11),
                stat("reference", 0.0, 30.0, 20, 20),
                stat("proven", 12.0, 30.0, 200, 200)), K20);
        assertEquals(0.25, w.get("momentum"), 1e-12, "a measured loss must cost conviction, flats and all");
        assertEquals(2.9688208544868906, w.get("proven"), 1e-12);
        assertTrue(w.get("momentum") < w.get("proven"));
    }

    @Test
    void aDeskWithNoPositiveEdgeAnywhereWeightsEqually() {
        // Deliberate, and narrower than the degenerate case ADR-0067 removed. The old
        // max(0, 2·hitRate−1) statistic collapsed to all-equal whenever any source merely fell below a
        // coin flip — with profitable sources still on the desk. This collapses only when EVERY source
        // has negative shrunk expectancy, and in that state the ADR-0064 edge gate passes no source, so
        // every name is reduce-only however the remaining conviction is shaped: there is no basis to
        // prefer one view of a book that is being wound down. Below zero the rule expresses no
        // ordering, because the positive part is taken before any ratio is formed.
        //
        // a: µ̂ =  −5, n = 180 → c = 0.9; pool₋ᵢ = −15 → ê = −6.0  → 0
        // b: µ̂ = −15, n = 180 → c = 0.9; pool₋ᵢ =  −5 → ê = −14.0 → 0
        var w = TelemetryWeights.compute(List.of(
                stat("a", -5.0, 40.0, 90, 90),
                stat("b", -15.0, 40.0, 90, 90)), K20);
        assertEquals(1.0, w.get("a"), 1e-9);
        assertEquals(1.0, w.get("b"), 1e-9);
    }

    @Test
    void weightsAreBoundedByMinAndMaxParams() {
        // Same inputs as the proportionality case (raw 1.991597 / 1.008403 / 0) under a tighter band.
        var tight = new TelemetryWeights.Params(20, 0.5, 1.5);
        var w = TelemetryWeights.compute(List.of(
                stat("strong", 12.0, 100.0, 200, 200),
                stat("mild", 6.0, 100.0, 200, 200),
                stat("loser", -12.0, 100.0, 200, 200)), tight);
        assertEquals(1.5, w.get("strong"), 1e-9);
        assertEquals(1.0084033613445378, w.get("mild"), 1e-12);
        assertEquals(0.5, w.get("loser"), 1e-9);
    }

    @Test
    void singleSourceGetsTheNeutralWeight() {
        // One source has no "rest of the desk" to shrink toward, so it is its own prior and the ratio
        // to its own mean is exactly 1.
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
        // No longer the weight link (ADR-0093), still the edge gate's hurdle conversion (ADR-0081).
        assertEquals(0.5, TelemetryWeights.standardNormalCdf(0.0), 1e-9);
        assertEquals(0.8413447, TelemetryWeights.standardNormalCdf(1.0), 1e-6);
        assertEquals(0.0249979, TelemetryWeights.standardNormalCdf(-1.96), 1e-6);
        assertEquals(0.9937903, TelemetryWeights.standardNormalCdf(2.5), 1e-6);
        // symmetry: Φ(x) + Φ(−x) = 1
        assertEquals(1.0, TelemetryWeights.standardNormalCdf(0.7)
                + TelemetryWeights.standardNormalCdf(-0.7), 1e-9);
    }
}
