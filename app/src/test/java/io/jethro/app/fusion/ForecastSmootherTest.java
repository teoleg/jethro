package io.jethro.app.fusion;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0088 — the fused conviction is averaged over the horizon its edge is measured on.
 *
 * <p>Worked example, the shipped configuration (30 s fusion cadence, 900 s selected rung):
 * <pre>
 *   alpha = 1 - exp(-30/900) = 1 - exp(-1/30) = 0.0327838995179941…
 *   cycle 1: f = +20  (first sight)     ⇒ average = +20                      (EWMA seed)
 *   cycle 2: f = -20  (a full flip)     ⇒ average = 20 + alpha·(-20 - 20)
 *                                                  = 20 - 1.311355980719764…
 *                                                  = 18.688644019280236…
 * </pre>
 * A complete sign flip of the raw view moves the conviction the desk sizes on by 1.31 out of 40 —
 * instead of swinging the target from +2x to -2x the unit notional and paying a round trip for it.
 */
class ForecastSmootherTest {

    private static final double TOL = 1e-12;

    @Test
    void alphaIsTheSameIdentityTheHoldingPeriodIsDerivedFrom() {
        // The ADR-0080 identity, evaluated on elapsed seconds instead of the nominal cycle.
        assertEquals(TargetPlanner.adjustmentRateFor(30, 900),
                ForecastSmoother.alphaFor(30.0, 900.0), TOL,
                "the signal filter and the trading rate must share one identity");
        // Step response e-folds in exactly one horizon: alpha over dt = h is 1 - 1/e.
        assertEquals(1.0 - Math.exp(-1.0), ForecastSmoother.alphaFor(900.0, 900.0), TOL);
        // Degenerate inputs fall back to "no memory" — the current reading stands. This filter can
        // never freeze the desk on a stale view.
        assertEquals(1.0, ForecastSmoother.alphaFor(30.0, 0.0), TOL);
        assertEquals(1.0, ForecastSmoother.alphaFor(Double.NaN, 900.0), TOL);
    }

    @Test
    void workedExample_aFullSignFlipMovesConvictionByOneAlphaOfTheGap() {
        var smoother = new ForecastSmoother();
        long t0 = 1_785_000_000_000L;

        assertEquals(20.0, smoother.smooth("MSFT", 20.0, t0, 900), TOL, "first sight seeds at the reading");

        double alpha = 1.0 - Math.exp(-1.0 / 30.0);
        double expected = 20.0 + alpha * (-20.0 - 20.0);
        assertEquals(18.688644019280236, expected, TOL, "the hand-computed example");
        assertEquals(expected, smoother.smooth("MSFT", -20.0, t0 + 30_000, 900), TOL);
        assertEquals(expected, smoother.current("MSFT").orElseThrow(), TOL);
        assertEquals(1, smoother.trackedNames());
    }

    @Test
    void aPersistentViewIsUntouched() {
        var smoother = new ForecastSmoother();
        long t = 1_785_000_000_000L;
        double v = 0;
        for (int i = 0; i < 200; i++) {
            v = smoother.smooth("ES", 12.5, t + i * 30_000L, 900);
        }
        assertEquals(12.5, v, TOL, "a constant forecast is its own average — a real trend sizes as before");
    }

    @Test
    void anOscillatingViewIsAveragedTowardsZeroAndFallsUnderTheConvictionFloor() {
        var smoother = new ForecastSmoother();
        long t = 1_785_000_000_000L;
        double last = 0;
        double peak = 0;
        // A +-20 square wave with a 4-minute half period — the live MSFT pathology (a name flipping
        // sign every ~8 cycles while its edge is measured over 900 s).
        for (int cycle = 0; cycle < 600; cycle++) {
            double raw = ((cycle / 8) % 2 == 0) ? 20.0 : -20.0;
            last = smoother.smooth("MSFT", raw, t + cycle * 30_000L, 900);
            if (cycle > 560) { // the last few half-periods, once the start-up transient has decayed
                peak = Math.max(peak, Math.abs(last));
            }
        }
        // Steady-state peak of a square wave through a first-order lag: A·tanh(T/2h).
        assertEquals(20.0 * Math.tanh(240.0 / 1800.0), peak, 1e-6);
        assertTrue(peak < 5.0,
                "an unhold-able view drops under the ADR-0059 conviction floor (5.0) — peak was " + peak);
        assertTrue(Math.abs(last) <= 20.0, "always inside the Carver band");
    }

    @Test
    void aLongGapReSeedsRatherThanResumingAStaleAverage() {
        var smoother = new ForecastSmoother();
        long t0 = 1_785_000_000_000L;
        smoother.smooth("GOOG", 20.0, t0, 900);
        // Name drops out of the cross-section for a day and returns with the opposite view.
        double after = smoother.smooth("GOOG", -20.0, t0 + 86_400_000L, 900);
        assertEquals(-20.0, after, 1e-9, "alpha → 1 over a gap ≫ h: the current reading stands");
    }

    @Test
    void filteringShrinksTheTargetOfAFlippingNameButNotOfAPersistentOne() {
        var smoother = new ForecastSmoother();
        long t0 = 1_785_000_000_000L;
        var params = new FusionPlanner.Params(0.5, BigDecimal.valueOf(50_000), 0.5, 1.0);
        Map<String, List<Forecast>> flip = Map.of("MSFT", List.of(Forecast.of("reversion", "MSFT", 20.0)));
        Map<String, List<Forecast>> flop = Map.of("MSFT", List.of(Forecast.of("reversion", "MSFT", -20.0)));

        var seed = plan(flip, params, (id, v) -> smoother.smooth(id, v, t0, 900));
        var flipped = plan(flop, params, (id, v) -> smoother.smooth(id, v, t0 + 30_000, 900));
        var raw = plan(flop, params, ForecastSmoother.NONE);

        // Unfiltered, one flip swings the target from +2x to -2x the unit notional; filtered, the desk
        // is still long and barely moved. The target is exact decimal on both paths (invariant 1).
        // 50,000 x (20/10) / (50 x 1) = 2,000 units at the cap, either way round.
        assertEquals(0, seed.get(0).targetQty().compareTo(new BigDecimal("2000.000000")));
        assertEquals(0, raw.get(0).targetQty().compareTo(new BigDecimal("-2000.000000")));
        assertTrue(flipped.get(0).targetQty().signum() > 0, "still long after one flip");
        assertTrue(flipped.get(0).targetQty().compareTo(seed.get(0).targetQty()) < 0, "and slightly smaller");
        // The book carries the number the desk actually traded on; the source's RAW reading stays
        // visible in the contributions, so the difference is auditable rather than hidden.
        assertEquals(-20.0, flipped.get(0).contributions().get(0).forecast(), TOL);
        assertTrue(flipped.get(0).combinedForecast() > 0);
    }

    @Test
    void theNoneFilterReproducesTheUnfilteredPlanExactly() {
        var params = new FusionPlanner.Params(0.5, BigDecimal.valueOf(50_000), 0.5, 1.0);
        Map<String, List<Forecast>> f = Map.of("AAPL", List.of(Forecast.of("trend", "AAPL", 13.0)));
        assertEquals(plan(f, params, null).get(0).targetQty(),
                plan(f, params, ForecastSmoother.NONE).get(0).targetQty());
        assertEquals(FusionPlanner.plan(f, List.of(), s -> 1.0, id -> BigDecimal.valueOf(50),
                        id -> BigDecimal.ONE, id -> BigDecimal.ZERO, params).get(0).targetQty(),
                plan(f, params, ForecastSmoother.NONE).get(0).targetQty());
    }

    private static List<FusionPlanner.Target> plan(Map<String, List<Forecast>> forecasts,
                                                   FusionPlanner.Params params,
                                                   ForecastSmoother.Smoothing smoothing) {
        return FusionPlanner.plan(forecasts, List.of(), s -> 1.0,
                id -> BigDecimal.valueOf(50),  // price $50
                id -> BigDecimal.ONE,          // equity: contract multiplier 1 (ADR-0078)
                id -> BigDecimal.ZERO,         // flat book
                params, smoothing);
    }
}
