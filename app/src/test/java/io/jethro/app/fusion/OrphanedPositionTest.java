package io.jethro.app.fusion;

import io.jethro.trading.algo.hypothesis.Hypothesis;
import io.jethro.domain.Side;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0065 — the target book spans the names we HOLD, not only the names we have a view on, and a
 * trade that takes risk off is not subject to the controls that ask whether a view is worth putting
 * risk on. Exact quantities throughout (invariant 1).
 */
class OrphanedPositionTest {

    private static final ForecastRegistry.Params PARAMS = new ForecastRegistry.Params(3.0, 5.0, 4.0, 20.0);
    // assumedCorrelation, unitNotional, bufferFraction, adjustmentRate
    private static final FusionPlanner.Params PLAN = new FusionPlanner.Params(0.5, BigDecimal.valueOf(10_000), 0.2, 0.5);

    private static BigDecimal qty(String v) {
        return new BigDecimal(v).setScale(6);
    }

    @Test
    void heldNameWithNoForecastIsPlannedFlatAndWorkedDown() {
        // The bug this fixes: GOOG was bought on a view, the view went silent, and because the
        // planner only ever saw the fresh cross-section the position was never revisited again.
        Map<String, List<Forecast>> noViews = Map.of();
        var targets = FusionPlanner.plan(noViews, List.of("GOOG"), s -> 1.0,
                id -> BigDecimal.valueOf(178), id -> BigDecimal.ONE, id -> BigDecimal.valueOf(131), PLAN);

        assertEquals(1, targets.size(), "a held name is planned even with no fresh forecast");
        FusionPlanner.Target t = targets.get(0);
        assertEquals("GOOG", t.instrument());
        assertEquals(0, t.sources(), "no source has a view on it");
        assertEquals(0.0, t.combinedForecast());
        assertEquals(0, t.targetQty().signum(), "no view ⇒ target is flat");
        // Partial adjustment toward zero at rate 0.5: 131 long → sell 65.5 this cycle.
        assertEquals(qty("-65.5"), t.deltaQty());
    }

    @Test
    void aNameWithAViewIsUnaffectedByBeingHeld() {
        var registry = new ForecastRegistry(PARAMS, 60_000);
        registry.submitHypothesis("AAPL", Side.BUY, Hypothesis.Conviction.HIGH);
        var forecasts = registry.byInstrument(System.currentTimeMillis());

        var withHeld = FusionPlanner.plan(forecasts, List.of("AAPL"), s -> 1.0,
                id -> BigDecimal.valueOf(196), id -> BigDecimal.ONE, id -> BigDecimal.valueOf(119), PLAN);
        var withoutHeld = FusionPlanner.plan(forecasts, List.of(), s -> 1.0,
                id -> BigDecimal.valueOf(196), id -> BigDecimal.ONE, id -> BigDecimal.valueOf(119), PLAN);

        assertEquals(1, withHeld.size());
        assertEquals(withoutHeld.get(0).targetQty(), withHeld.get(0).targetQty(),
                "listing a name as held must not change the target when it has a view");
        assertEquals(withoutHeld.get(0).deltaQty(), withHeld.get(0).deltaQty());
    }

    @Test
    void unwindSurvivesTheReduceOnlyProjection() {
        // The edge gate being shut is exactly when unwinding matters most: it must not clamp the exit.
        BigDecimal current = BigDecimal.valueOf(131);
        BigDecimal delta = TargetPlanner.orderDelta(BigDecimal.ZERO, current, 0.2, 0.5);
        assertEquals(qty("-65.5"), TargetPlanner.reduceOnly(delta, current),
                "reduce-only passes a toward-flat delta through untouched");
    }

    @Test
    void unwindConvergesToFlatAndStops() {
        // Walk the real loop: each cycle plans a toward-flat step, the executor truncates it to whole
        // units (FusionExecutor.route), and the fill moves the position. Must terminate, monotonically,
        // without ever crossing through flat into a fresh position on the other side.
        BigDecimal position = BigDecimal.valueOf(131);
        int cycles = 0;
        while (cycles < 50) {
            BigDecimal planned = TargetPlanner.orderDelta(BigDecimal.ZERO, position, 0.2, 0.5);
            BigDecimal filled = planned.setScale(0, java.math.RoundingMode.DOWN); // whole units only
            if (filled.signum() == 0) {
                break; // nothing tradable left
            }
            assertTrue(TargetPlanner.isRiskReducing(filled, position), "every traded step reduces risk");
            BigDecimal next = position.add(filled);
            assertTrue(next.abs().compareTo(position.abs()) < 0, "monotonically toward flat");
            assertTrue(next.signum() == 0 || next.signum() == position.signum(),
                    "never crosses through flat into a new position");
            position = next;
            cycles++;
        }
        assertTrue(cycles < 50, "terminates rather than trading forever");
        // Partial adjustment approaches flat asymptotically and the executor will not trade a
        // sub-unit clip, so a whole-unit instrument settles one unit short of flat. That residual is
        // bounded, never grows, and is a pre-existing property of the whole-unit order path.
        assertEquals(BigDecimal.ONE, position, "settles at the smallest tradable residual");
    }

    @Test
    void riskReducingIsSignAndMagnitudeOnly() {
        assertTrue(TargetPlanner.isRiskReducing(BigDecimal.valueOf(-65), BigDecimal.valueOf(131)));
        assertTrue(TargetPlanner.isRiskReducing(BigDecimal.valueOf(-131), BigDecimal.valueOf(131)),
                "closing outright reduces");
        assertTrue(TargetPlanner.isRiskReducing(BigDecimal.valueOf(50), BigDecimal.valueOf(-131)),
                "buying back a short reduces");
        assertFalse(TargetPlanner.isRiskReducing(BigDecimal.valueOf(20), BigDecimal.valueOf(131)),
                "adding to a long is not a reduction");
        assertFalse(TargetPlanner.isRiskReducing(BigDecimal.valueOf(100), BigDecimal.ZERO),
                "opening from flat is not a reduction");
        assertFalse(TargetPlanner.isRiskReducing(BigDecimal.valueOf(-400), BigDecimal.valueOf(131)),
                "a flip that overshoots (long 131 → short 269) is bigger risk, not smaller");
        assertFalse(TargetPlanner.isRiskReducing(BigDecimal.ZERO, BigDecimal.valueOf(131)));
    }

    @Test
    void heldNameWithoutAMarkStillGetsAnExit() {
        // A stale/absent mark must not be a reason to keep an unsupported position: the target is
        // zero regardless of price, and the delta is pure quantity arithmetic.
        var targets = FusionPlanner.plan(Map.of(), List.of("SAP"), s -> 1.0,
                id -> null, id -> BigDecimal.ONE, id -> BigDecimal.valueOf(-96), PLAN);
        assertEquals(qty("48"), targets.get(0).deltaQty(), "short 96 with no view → buy back half");
    }
}
