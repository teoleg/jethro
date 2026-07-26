package io.jethro.app.fusion;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact-value tests for the ADR-0055 phase-3 fusion engine: weighted combination + diversification
 *  multiplier, target sizing, and the Gârleanu-Pedersen partial-adjustment no-trade band. */
class FusionEngineTest {

    private static ForecastCombiner.Weighted wf(String src, double value, double weight) {
        return new ForecastCombiner.Weighted(Forecast.of(src, "AAPL", value), weight);
    }

    @Test
    void equalWeightAverageThenDiversificationMultiplier() {
        // Two agreeing forecasts (+10, +10), equal weight, ρ=0.5.
        var c = ForecastCombiner.combine("AAPL", List.of(wf("a", 10, 1), wf("b", 10, 1)), 0.5);
        // Weighted average = 10; DM(n=2, ρ=0.5) = 1/sqrt(0.5 + 0.5·0.5) = 1/sqrt(0.75) ≈ 1.1547.
        double dm = 1.0 / Math.sqrt(0.75);
        assertEquals(dm, c.diversificationMultiplier(), 1e-9);
        assertEquals(10.0 * dm, c.value(), 1e-9);
        assertEquals(2, c.activeSources());
    }

    @Test
    void disagreementCancels() {
        var c = ForecastCombiner.combine("AAPL", List.of(wf("a", 12, 1), wf("b", -12, 1)), 0.5);
        assertEquals(0.0, c.value(), 1e-12, "equal-and-opposite forecasts net to no view");
    }

    @Test
    void weightsTiltTheAverageAndSetTheDiversificationMultiplier() {
        // +20 at weight 3, −20 at weight 1 → avg = (60−20)/4 = 10, then the ADR-0076 DM.
        // Normalised weights (0.75, 0.25) ⇒ Σw² = 0.5625 + 0.0625 = 0.625 (effective breadth 1.6, not 2).
        // variance = 0.625 + (1 − 0.625)·0.5 = 0.8125 ⇒ DM = 1/√0.8125 = 1.1094003924504583.
        var c = ForecastCombiner.combine("AAPL", List.of(wf("a", 20, 3), wf("b", -20, 1)), 0.5);
        assertEquals(1.1094003924504583, c.diversificationMultiplier(), 1e-12);
        assertEquals(11.094003924504583, c.value(), 1e-12);
        // Strictly below the count rule's 1/√0.75 = 1.1547…: two views held 3:1 are not two equal views.
        assertTrue(c.diversificationMultiplier() < 1.0 / Math.sqrt(0.75));
    }

    @Test
    void diversificationMultiplierIsCappedAndMonotone() {
        assertEquals(1.0, ForecastCombiner.diversificationMultiplier(1, 0.5), 1e-12);
        assertTrue(ForecastCombiner.diversificationMultiplier(10, 0.2)
                >= ForecastCombiner.diversificationMultiplier(3, 0.2), "more sources → ≥ DM");
        assertTrue(ForecastCombiner.diversificationMultiplier(1000, 0.0)
                <= ForecastCombiner.MAX_DIVERSIFICATION_MULTIPLIER + 1e-9, "capped at 2.5");
    }

    @Test
    void equalWeightsReproduceTheCountRuleExactly() {
        // ADR-0076 generalises the count rule; at equal weights (Σw² = 1/n) it must BE the count rule —
        // which is the cold start and weights.mode=equal, so neither path changes behaviour.
        for (int n = 1; n <= 8; n++) {
            for (double rho : new double[] {0.0, 0.25, 0.5, 0.9, 1.0}) {
                assertEquals(ForecastCombiner.diversificationMultiplier(n, rho),
                        ForecastCombiner.diversificationMultiplierForConcentration(1.0 / n, rho), 1e-12,
                        "n=" + n + " rho=" + rho);
            }
        }
    }

    @Test
    void concentratingTheWeightsCanOnlyShrinkTheMultiplier() {
        // The safety property (ADR-0076): Σw² ≥ 1/n by Cauchy–Schwarz and the DM is decreasing in Σw²,
        // so re-weighting never grows the book. Equal weights are the maximum; all-on-one is DM 1.
        double equal = ForecastCombiner.diversificationMultiplierForConcentration(1.0 / 4, 0.5);
        double tilted = ForecastCombiner.diversificationMultiplierForConcentration(0.40, 0.5);
        double allOnOne = ForecastCombiner.diversificationMultiplierForConcentration(1.0, 0.5);
        assertTrue(tilted < equal, "more concentrated → smaller DM");
        assertEquals(1.0, allOnOne, 1e-12, "one view earns no diversification");
        assertTrue(allOnOne <= tilted && tilted <= equal);
        // …and never below 1: averaging cannot make the desk more confident than a single view.
        assertTrue(ForecastCombiner.diversificationMultiplierForConcentration(0.99, 0.0) >= 1.0);
    }

    @Test
    void aFloorWeightedSourceNoLongerBuysAFullExtraUnitOfLeverage() {
        // The live shape this change targets: telemetry down-weighted `trend` to the MIN floor (0.25)
        // for measured-negative expectancy while `reversion` sat near the top of the band. Under the
        // count rule that pair still earned the full two-source multiplier; now it earns the breadth
        // the 10:1 weighting actually delivers.
        var c = ForecastCombiner.combine("AAPL",
                List.of(wf("reversion", 10, 2.5), wf("trend", 10, 0.25)), 0.5);
        assertEquals(2, c.activeSources(), "both sources still contribute — this is not a silencing");
        assertTrue(c.diversificationMultiplier() < 1.0 / Math.sqrt(0.75),
                "a 10:1 weight split is closer to one view than to two");
        assertTrue(c.value() < 10.0 * (1.0 / Math.sqrt(0.75)), "…so the target book is smaller");
        assertTrue(c.value() >= 10.0, "…but never smaller than the un-diversified view");
    }

    @Test
    void noSourcesIsNoView() {
        var c = ForecastCombiner.combine("AAPL", List.of(), 0.5);
        assertEquals(0.0, c.value(), 1e-12);
        assertEquals(0, c.activeSources());
    }

    @Test
    void targetSizingScalesWithForecast() {
        // forecast = TARGET_ABS (10) at $100 with $10,000 unit → 1.0× unit → 100 shares.
        assertEquals(0, new BigDecimal("100.000000").compareTo(
                TargetPlanner.targetQuantity(10.0, bd(10_000), bd(100))));
        // cap forecast (20) → 2× unit → 200 shares; short at −20 → −200.
        assertEquals(0, new BigDecimal("200.000000").compareTo(
                TargetPlanner.targetQuantity(20.0, bd(10_000), bd(100))));
        assertEquals(0, new BigDecimal("-200.000000").compareTo(
                TargetPlanner.targetQuantity(-20.0, bd(10_000), bd(100))));
        assertEquals(0, BigDecimal.ZERO.compareTo(
                TargetPlanner.targetQuantity(10.0, bd(10_000), BigDecimal.ZERO)), "no price → no target");
    }

    @Test
    void partialAdjustmentRespectsBandAndRate() {
        // target 100, current 95: gap 5, band = 100·0.2 = 20 → inside → no trade.
        assertEquals(0, BigDecimal.ZERO.compareTo(
                TargetPlanner.orderDelta(bd(100), bd(95), 0.2, 0.5)));
        // target 100, current 40: gap 60 > band 20 → trade 0.5·60 = 30.
        assertEquals(0, new BigDecimal("30.000000").compareTo(
                TargetPlanner.orderDelta(bd(100), bd(40), 0.2, 0.5)));
        // exit toward a zero target always trades (band collapses to 0): 0.5·(0−80) = −40.
        assertEquals(0, new BigDecimal("-40.000000").compareTo(
                TargetPlanner.orderDelta(BigDecimal.ZERO, bd(80), 0.2, 0.5)));
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v);
    }
}
