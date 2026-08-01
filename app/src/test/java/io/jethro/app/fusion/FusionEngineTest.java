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
        // ADR-0119/0124: sources of one mind earn agreement 1 exactly (zero dispersion about the
        // average), so this path is untouched by either.
        assertEquals(1.0, c.agreement(), 1e-12);
    }

    @Test
    void disagreementCancels() {
        var c = ForecastCombiner.combine("AAPL", List.of(wf("a", 12, 1), wf("b", -12, 1)), 0.5);
        assertEquals(0.0, c.value(), 1e-12, "equal-and-opposite forecasts net to no view");
        assertEquals(0.0, c.agreement(), 1e-12, "…which is agreement zero, the ADR-0119 limit");
    }

    @Test
    void oneSourceIsNotACorroboratedView() {
        // The ADR-0124 defect, live on 2026-07-29: META and TSLA were called by `xsreversion` ALONE,
        // ADR-0119's sign ratio returned 1 because there was nothing to disagree with, and those two
        // uncorroborated names then carried the LARGEST combined forecasts in the cross-section —
        // |−15.41| against a best-corroborated |−8.44| over three sources. Breadth was inverted.
        var alone = ForecastCombiner.combine("META",
                List.of(wf("xsreversion", -15.411840183349527, 0.6415022359419683)), 0.5);
        assertEquals(1, alone.activeSources());
        assertEquals(1.0, alone.diversificationMultiplier(), 1e-12, "one view earns no diversification");
        assertEquals(0.0, alone.agreement(), 1e-12,
                "one effective source ⇒ zero residual d.f. ⇒ the dispersion is UNESTIMABLE, not zero");
        assertEquals(0.0, alone.value(), 1e-12, "…so the desk takes no position on an untested view");

        // All the weight on one forecast is the same case even when several sources are nominally
        // present: the SECOND source contributing nothing leaves nothing to corroborate with.
        var effectivelyAlone = ForecastCombiner.combine("META",
                List.of(wf("xsreversion", -15.411840183349527, 1.0), wf("trend", 0, 0)), 0.5);
        assertEquals(0.0, effectivelyAlone.agreement(), 1e-12);

        // Self-healing, not a ban: a second sensor waking up on the same view restores it in full.
        var corroborated = ForecastCombiner.combine("META",
                List.of(wf("xsreversion", -15.411840183349527, 0.6415022359419683),
                        wf("trend", -15.411840183349527, 0.6415022359419683)), 0.5);
        assertEquals(1.0, corroborated.agreement(), 1e-12);
        assertTrue(Math.abs(corroborated.value()) > Math.abs(alone.value()),
                "corroboration is what earns size — the inversion is gone");
    }

    @Test
    void sourcesThatShareASignButNotAMagnitudeAreNotFullyCorroborated() {
        // ADR-0124 is strictly smoother than the sign ratio it replaces: +10 and +20 agree on the
        // direction and disagree on the size, and the desk's confidence in the LEVEL it sizes off is
        // lower than if both had said +15. ADR-0119 scored this 1.0 — sign-blind.
        var c = ForecastCombiner.combine("AAPL", List.of(wf("a", 10, 1), wf("b", 20, 1)), 0.5);
        // μ̂ = 15; Σŵ(f−μ̂)² = 25; Σŵ² = 0.5 ⇒ s² = 25/0.5 = 50; 15/√(225+50) = 0.9045340337332908.
        assertEquals(0.9045340337332908, c.agreement(), 1e-12);
        assertTrue(c.agreement() < 1.0 && c.agreement() > 0.9, "shrunk, but only a little");
        // …and it degrades continuously toward the agreeing case, so no name flips on sensor noise.
        var closer = ForecastCombiner.combine("AAPL", List.of(wf("a", 14, 1), wf("b", 16, 1)), 0.5);
        assertTrue(closer.agreement() > c.agreement() && closer.agreement() < 1.0);
    }

    @Test
    void weightsTiltTheAverageAndSetTheDiversificationMultiplier() {
        // +20 at weight 3, −20 at weight 1 → avg = (60−20)/4 = 10, then the ADR-0076 DM.
        // Normalised weights (0.75, 0.25) ⇒ Σw² = 0.5625 + 0.0625 = 0.625 (effective breadth 1.6, not 2).
        // variance = 0.625 + (1 − 0.625)·0.5 = 0.8125 ⇒ DM = 1/√0.8125 = 1.1094003924504583.
        var c = ForecastCombiner.combine("AAPL", List.of(wf("a", 20, 3), wf("b", -20, 1)), 0.5);
        assertEquals(1.1094003924504583, c.diversificationMultiplier(), 1e-12);
        // ADR-0124: μ̂ = 10 with the sources 30 apart. Σŵ(f−μ̂)² = 0.75·10² + 0.25·(−30)² = 300, and
        // Σŵ² = 0.625 ⇒ s² = 300/(1 − 0.625) = 800, so agreement = 10/√(100 + 800) = 10/30 = 1/3. Two
        // thirds of the scale the average claims is disagreement between the sources, not view — the
        // mean alone hid that. (ADR-0119's sign ratio scored this 0.5, blind to the magnitudes.)
        assertEquals(1.0 / 3.0, c.agreement(), 1e-12);
        assertEquals(3.6980013081681946, c.value(), 1e-12);
        // Strictly below the count rule's 1/√0.75 = 1.1547…: two views held 3:1 are not two equal views.
        assertTrue(c.diversificationMultiplier() < 1.0 / Math.sqrt(0.75));
    }

    @Test
    void agreementIsOneWayAndNeverTouchesSourcesThatSayTheSameThing() {
        // The ADR-0119/0124 safety property, over the whole forecast grid: the scalar is in [0,1], it is
        // EXACTLY 1 whenever the contributing forecasts are identical (so those names are byte-identical
        // to the pre-ADR-0124 desk), and it never flips a sign.
        for (double a = -20; a <= 20; a += 2.5) {
            for (double b = -20; b <= 20; b += 2.5) {
                var c = ForecastCombiner.combine("AAPL", List.of(wf("x", a, 1.7), wf("y", b, 0.4)), 0.5);
                assertTrue(c.agreement() >= 0.0 && c.agreement() <= 1.0, "a=" + a + " b=" + b);
                if (a == b && a != 0) {
                    assertEquals(1.0, c.agreement(), 1e-12, "identical ⇒ no shrinkage: " + a + "," + b);
                }
                double unscaled = (1.7 * a + 0.4 * b) / 2.1 * c.diversificationMultiplier();
                assertTrue(Math.abs(c.value()) <= Math.abs(unscaled) + 1e-12, "can only shrink");
                assertTrue(c.value() == 0.0 || Math.signum(c.value()) == Math.signum(unscaled),
                        "sign is never flipped");
            }
        }
    }

    @Test
    void agreementIsMonotoneInHowFarApartTheSourcesAre() {
        // The property ADR-0119's sign ratio did not have: the scalar responds to the SIZE of the
        // disagreement, not only to whether a sign flipped. Widening the gap around a fixed mean can
        // only shrink the position, continuously and without a cliff.
        double previous = Double.MAX_VALUE;
        for (double gap = 0; gap <= 10; gap += 1.25) {   // ±10 around 10 stays inside the ±20 clamp
            var c = ForecastCombiner.combine("AAPL",
                    List.of(wf("x", 10 - gap, 1), wf("y", 10 + gap, 1)), 0.5);
            assertEquals(1.0 / Math.sqrt(1.0 + 2.0 * gap * gap / 100.0), c.agreement(), 1e-12,
                    "1/√(1 + (s/μ̂)²) with s² = 2·gap² at equal weights, μ̂ = 10");
            assertTrue(c.agreement() <= previous, "wider disagreement ⇒ never a larger position");
            previous = c.agreement();
        }
    }

    @Test
    void aResidualOfTwoFightingSensorsIsNotAFullConvictionPosition() {
        // The live shape this change targets (2026-07-28): the ONE name the desk held was the one whose
        // sensors flatly contradicted each other — trend +11.22 against reversion −10.41 — and the desk
        // sized off the small residual as if it were a settled view, then hedged that position too.
        var c = ForecastCombiner.combine("AAPL",
                List.of(wf("trend", 11.222357223010645, 0.7940890068032261),
                        wf("reversion", -10.408272759827817, 1.0968379620104083)), 0.5);
        // The pair averages to −1.3245557454277905 while sitting 21.63 apart, so almost all of the
        // scale is dispersion: ADR-0124's scalar is 0.08627672501946398 (ADR-0119's sign ratio read
        // 0.12321282549722257 here — the same verdict, reached from the magnitudes rather than the
        // signs, and a shade more conservative).
        assertEquals(0.08627672501946398, c.agreement(), 1e-12, "≈ 9% of the conviction survived");
        // The two sensors still net short, but at a twelfth of the size the mean alone claimed.
        assertTrue(c.value() < 0, "the sign the average found is kept");
        assertEquals(-0.1313970740218512, c.value(), 1e-12);
        assertTrue(Math.abs(c.value()) < 0.09 * 1.5229724354072096,
                "…at a fraction of the conviction the desk was sizing off");
        // Contrast: the SAME net view from sources that agree is untouched.
        var agreeing = ForecastCombiner.combine("AAPL",
                List.of(wf("trend", -1.3245557454277905, 0.7940890068032261),
                        wf("reversion", -1.3245557454277905, 1.0968379620104083)), 0.5);
        assertEquals(1.0, agreeing.agreement(), 1e-12);
        // Same weighted mean, same DM — the ONLY difference is that these sources agree, and the whole
        // of that difference is the agreement scalar: the fighting pair gets 12% of the position.
        assertEquals(c.agreement(), c.value() / agreeing.value(), 1e-9);
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
     * ADR-0090 (narrowing ADR-0080): a FLAT target is an exit and trades in full; every other move of
     * the target is a rebalance and is rated on the whole gap, in whichever direction it lies. Exact
     * decimal on every leg.
     */
    @Test
    void onlyAFlatTargetIsWorkedInFull() {
        double rate = 0.01; // a clean rate so the arithmetic is checkable by hand
        // flat → long 150: nothing to reduce, so the whole gap is rated: 0.01·150 = 1.5.
        assertEquals(0, new BigDecimal("1.500000").compareTo(
                TargetPlanner.orderDelta(bd(150), BigDecimal.ZERO, 0.5, rate)));
        // long 100 → target 10 (band = 10·0.5 = 5, cleared): the desk still holds a view, so this is a
        // TRIM, not a cut — 0.01·(−90) = −0.9, where ADR-0080 dumped the whole −90.
        assertEquals(0, new BigDecimal("-0.900000").compareTo(
                TargetPlanner.orderDelta(bd(10), bd(100), 0.5, rate)));
        // long 100 → short 200 (band = 200·0.5 = 100, cleared by |−300|): the view REVERSED, which is a
        // change of mind and not a danger cut — 0.01·(−300) = −3, where ADR-0080 round-tripped −102.
        assertEquals(0, new BigDecimal("-3.000000").compareTo(
                TargetPlanner.orderDelta(bd(-200), bd(100), 0.5, rate)));
        // short 100 → target −150: the gap −50 grows the short, so it is rated: 0.01·(−50) = −0.5.
        assertEquals(0, new BigDecimal("-0.500000").compareTo(
                TargetPlanner.orderDelta(bd(-150), bd(-100), 0.2, rate)));
        // ...and the exit is untouched: target 0 collapses the band and trades the whole gap, so the
        // ADR-0086 risk cut and the ADR-0065 orphan unwind still leave in one cycle.
        assertEquals(0, new BigDecimal("-100.000000").compareTo(
                TargetPlanner.orderDelta(BigDecimal.ZERO, bd(100), 0.5, rate)));
        assertEquals(0, new BigDecimal("100.000000").compareTo(
                TargetPlanner.orderDelta(BigDecimal.ZERO, bd(-100), 0.5, rate)));
    }

    /**
     * ADR-0090: with the rate on both halves, the position is the exponential smoother the ADR-0080
     * identity describes — {@code posₜ₊₁ = (1−a)·posₜ + a·aim} — so it e-folds toward a STANDING aim in
     * exactly one horizon whether it is walking up to it or down to it. The asymmetric policy could
     * only ever satisfy that on the way up.
     */
    @Test
    void exposureEFoldsTowardTheAimInOneHorizonInBothDirections() {
        double a = TargetPlanner.adjustmentRateFor(30, 3600); // 120 cycles to one horizon
        for (BigDecimal[] leg : new BigDecimal[][] {
                {bd(1000), BigDecimal.ZERO},   // walking UP to the aim
                {bd(100), bd(1000)},           // walking DOWN to a smaller, same-signed aim
                {bd(-500), bd(500)}}) {        // walking THROUGH flat to a reversed aim
            BigDecimal aim = leg[0];
            BigDecimal pos = leg[1];
            BigDecimal gap0 = aim.subtract(pos);
            for (int cycle = 0; cycle < 120; cycle++) {
                // no band, so the walk is never truncated and the decay is the rate's alone
                pos = pos.add(TargetPlanner.orderDelta(aim, pos, 0.0, a));
            }
            double remaining = aim.subtract(pos).abs().doubleValue() / gap0.abs().doubleValue();
            assertEquals(Math.exp(-1), remaining, 1e-4, "one horizon leaves 1/e of the gap: " + aim);
        }
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
