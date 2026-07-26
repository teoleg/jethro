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
        // forecast = TARGET_ABS (10) at $100 with $10,000 unit → 1.0× unit → 100 shares (multiplier 1).
        assertEquals(0, new BigDecimal("100.000000").compareTo(
                TargetPlanner.targetQuantity(10.0, bd(10_000), bd(100), BigDecimal.ONE)));
        // cap forecast (20) → 2× unit → 200 shares; short at −20 → −200.
        assertEquals(0, new BigDecimal("200.000000").compareTo(
                TargetPlanner.targetQuantity(20.0, bd(10_000), bd(100), BigDecimal.ONE)));
        assertEquals(0, new BigDecimal("-200.000000").compareTo(
                TargetPlanner.targetQuantity(-20.0, bd(10_000), bd(100), BigDecimal.ONE)));
        assertEquals(0, BigDecimal.ZERO.compareTo(
                TargetPlanner.targetQuantity(10.0, bd(10_000), BigDecimal.ZERO, BigDecimal.ONE)),
                "no price → no target");
    }

    /**
     * ADR-0078 — the target is CASH-at-risk, so it divides by the money value of one unit of the
     * instrument, {@code price × contractMultiplier}, not by price.
     *
     * <p>Worked example, exact decimal. Unit notional $50,000, forecast at TARGET_ABS (10) ⇒ fraction 1.0.
     * <ul>
     *   <li><b>Equity</b> at $200, multiplier 1: qty = 50,000 / (200 × 1) = 250 shares.
     *       Exposure = 250 × 200 × 1 = $50,000 ✔ (unchanged by this ADR).</li>
     *   <li><b>ES future</b> at 5,000, multiplier 50: qty = 50,000 / (5,000 × 50) = 0.2 contracts.
     *       Exposure = 0.2 × 5,000 × 50 = $50,000 ✔. Dividing by price alone gave 10 contracts —
     *       exposure 10 × 5,000 × 50 = $2,500,000, i.e. 50× the cash asked for.</li>
     *   <li><b>ZF note future</b> at 100, multiplier 1,000: qty = 50,000 / (100 × 1,000) = 0.5 contracts.
     *       Exposure = 0.5 × 100 × 1,000 = $50,000 ✔ (price alone gave 500 ⇒ $50,000,000).</li>
     * </ul>
     * The invariant asserted below is the one that matters: {@code |qty| × price × multiplier} — the same
     * arithmetic {@code PositionRisk} uses for {@code netExposure} — equals the cash asked for, for every
     * asset class.
     */
    @Test
    void targetIsCashAtRiskInTheInstrumentsOwnContractTerms() {
        record Case(String name, BigDecimal price, BigDecimal multiplier, String expectedQty) {
        }
        List<Case> cases = List.of(
                new Case("equity", bd(200), BigDecimal.ONE, "250.000000"),
                new Case("ES", bd(5_000), bd(50), "0.200000"),
                new Case("ZF", bd(100), bd(1_000), "0.500000"));
        for (Case c : cases) {
            BigDecimal qty = TargetPlanner.targetQuantity(10.0, bd(50_000), c.price(), c.multiplier());
            assertEquals(0, new BigDecimal(c.expectedQty()).compareTo(qty), c.name() + " target quantity");
            assertEquals(0, bd(50_000).compareTo(qty.multiply(c.price()).multiply(c.multiplier())),
                    c.name() + " exposure must equal the unit notional asked for");
        }
        // An unknown contract spec is not sized as if it were a share — no invented number (invariant 7).
        assertEquals(0, BigDecimal.ZERO.compareTo(
                TargetPlanner.targetQuantity(10.0, bd(50_000), bd(100), null)), "no spec → no target");
        assertEquals(0, BigDecimal.ZERO.compareTo(
                TargetPlanner.targetQuantity(10.0, bd(50_000), bd(100), BigDecimal.ZERO)),
                "non-positive multiplier → no target");
    }

    /** ADR-0078: rounding is in contract terms, and always toward zero. */
    @Test
    void tradableQuantityRoundsInContractTerms() {
        // multiplier 1 (share/FX unit): whole units, toward zero — unchanged behaviour, both signs.
        assertEquals(0, new BigDecimal("3").compareTo(
                TargetPlanner.tradableQuantity(new BigDecimal("3.9"), BigDecimal.ONE)));
        assertEquals(0, new BigDecimal("-3").compareTo(
                TargetPlanner.tradableQuantity(new BigDecimal("-3.9"), BigDecimal.ONE)));
        assertEquals(0, BigDecimal.ZERO.compareTo(
                TargetPlanner.tradableQuantity(new BigDecimal("0.9"), BigDecimal.ONE)), "dust still drops");
        // a CONTRACT keeps the quantity scale the order/fill records carry — 0.175988 ES is a real order
        // (the ADR-0039 hedge advisor already submits fractional ES), not dust to be rounded to nothing.
        assertEquals(0, new BigDecimal("0.175988").compareTo(
                TargetPlanner.tradableQuantity(new BigDecimal("0.1759884"), bd(50))));
        assertEquals(0, new BigDecimal("-0.175988").compareTo(
                TargetPlanner.tradableQuantity(new BigDecimal("-0.1759889"), bd(50))));
        // unknown spec ⇒ not quoted per unit ⇒ keep the scale rather than round a contract away
        assertEquals(0, new BigDecimal("0.500000").compareTo(
                TargetPlanner.tradableQuantity(new BigDecimal("0.5"), null)));
    }

    @Test
    void partialAdjustmentRespectsBandAndRate() {
        // target 100, current 95: gap 5, band = 100·0.2 = 20 → inside → no trade.
        assertEquals(0, BigDecimal.ZERO.compareTo(
                TargetPlanner.orderDelta(bd(100), bd(95), 0.2, 0.5)));
        // target 100, current 40: gap 60 > band 20, all of it INCREASES the long → 0.5·60 = 30.
        assertEquals(0, new BigDecimal("30.000000").compareTo(
                TargetPlanner.orderDelta(bd(100), bd(40), 0.2, 0.5)));
        // ADR-0080: exiting toward a zero target trades the WHOLE gap — the rate never slows a cut.
        assertEquals(0, new BigDecimal("-80.000000").compareTo(
                TargetPlanner.orderDelta(BigDecimal.ZERO, bd(80), 0.2, 0.5)));
    }

    /**
     * ADR-0080: cut in full, add at the derived rate. The gap is split at flat and only the part that
     * grows |position| is rated; exact decimal on every leg.
     */
    @Test
    void onlyTheRiskIncreasingPartOfADeltaIsRated() {
        double rate = 0.01; // a clean rate so the arithmetic is checkable by hand
        // flat → long 150: nothing to reduce, so the whole gap is rated: 0.01·150 = 1.5.
        assertEquals(0, new BigDecimal("1.500000").compareTo(
                TargetPlanner.orderDelta(bd(150), BigDecimal.ZERO, 0.5, rate)));
        // long 100 → target 10: gap −90 runs against the position and is inside it, so it is ALL a
        // reduction → trade −90 in full (band = 10·0.5 = 5, cleared).
        assertEquals(0, new BigDecimal("-90.000000").compareTo(
                TargetPlanner.orderDelta(bd(10), bd(100), 0.5, rate)));
        // long 100 → short 200: gap −300 = −100 to flat (full) + −200 of new short (rated):
        // −100 + 0.01·(−200) = −102.
        assertEquals(0, new BigDecimal("-102.000000").compareTo(
                TargetPlanner.orderDelta(bd(-200), bd(100), 0.5, rate)));
        // short 100 → target −150: the gap −50 grows the short, so it is rated: 0.01·(−50) = −0.5.
        assertEquals(0, new BigDecimal("-0.500000").compareTo(
                TargetPlanner.orderDelta(bd(-150), bd(-100), 0.2, rate)));
    }

    /**
     * ADR-0080: the rate is the solution of τ = −cycle/ln(1−a) = horizon, so a round trip through the
     * identity must return the horizon it was derived from — that identity IS the provenance.
     */
    @Test
    void adjustmentRateMakesTheHoldingPeriodEqualTheEvidenceHorizon() {
        for (long horizon : new long[] {60, 300, 900, 3600, 86_400}) {
            double a = TargetPlanner.adjustmentRateFor(30, horizon);
            double tau = -30.0 / Math.log(1 - a);
            assertEquals(horizon, tau, horizon * 1e-9, "exposure e-folds in exactly one horizon");
        }
        // The shipped configuration: 30s cycle against the 3600s telemetry horizon.
        assertEquals(0.008298707361, TargetPlanner.adjustmentRateFor(30, 3600), 1e-12);
        // A horizon at or below the cycle cannot be smoothed — take the whole gap, never more.
        assertEquals(1.0, TargetPlanner.adjustmentRateFor(3600, 1), 1e-12);
        // Degenerate configuration must still yield a usable rate rather than a division trap.
        assertTrue(TargetPlanner.adjustmentRateFor(0, 0) > 0);
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v);
    }
}
