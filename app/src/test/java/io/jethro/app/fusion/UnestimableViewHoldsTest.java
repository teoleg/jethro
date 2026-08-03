package io.jethro.app.fusion;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0135 — an UNESTIMABLE view is not a view of flat. When every unit of weight sits on one source
 * the ADR-0124 agreement scalar has zero residual degrees of freedom and returns 0, which zeroes the
 * combined forecast; sizing off that zero read "no corroboration" as "be flat" and — because ADR-0090
 * works a flat target in FULL rather than at the partial-adjustment rate — liquidated the whole book
 * in a single cycle.
 *
 * <p>The live case this reproduces (2026-07-31 20:10–20:49Z, read from `recent_orders` via the ADR-0134
 * origination trigger): the equity tape stopped printing at the cash close, the print-driven sensors
 * (ADR-0113) fell silent, breadth collapsed from three or four sources to one, and every routed name
 * exited on `fusion exit — target decayed to flat [forecast=-0.0, sources=1]`. The desk was still flat
 * three days later.
 *
 * <p>Exact quantities throughout (invariant 1); nothing here asserts a money figure.
 */
class UnestimableViewHoldsTest {

    // assumedCorrelation, unitNotional, bufferFraction, adjustmentRate
    private static final FusionPlanner.Params PLAN =
            new FusionPlanner.Params(0.5, BigDecimal.valueOf(10_000), 0.2, 0.5);

    private static BigDecimal qty(String v) {
        return new BigDecimal(v).setScale(6);
    }

    private static Map<String, List<Forecast>> views(Forecast... forecasts) {
        return Map.of(forecasts[0].instrument(), List.of(forecasts));
    }

    // ---- the combiner: which zero is which ----

    @Test
    void oneEffectiveSourceIsUnestimableNotFlat() {
        var c = ForecastCombiner.combine("GOOG",
                List.of(new ForecastCombiner.Weighted(Forecast.of("xsreversion", "GOOG", -10.969915627928405),
                        0.3762324120944941)), 0.5);
        assertEquals(1, c.activeSources());
        assertEquals(0.0, c.agreement(), 1e-12, "Σŵ² = 1 ⇒ zero residual dof ⇒ dispersion unestimable");
        assertEquals(0.0, c.value(), 1e-12, "which zeroes the combined value, as ADR-0124 intends");
        assertFalse(c.estimable(), "…but the zero is an ABSENCE of measurable conviction, not a view of flat");
    }

    @Test
    void twoSourcesNettingToZeroIsAMeasuredViewOfFlat() {
        var c = ForecastCombiner.combine("AAPL",
                List.of(new ForecastCombiner.Weighted(Forecast.of("trend", "AAPL", 12.0), 1.0),
                        new ForecastCombiner.Weighted(Forecast.of("reversion", "AAPL", -12.0), 1.0)), 0.5);
        assertEquals(0.0, c.value(), 1e-12);
        assertTrue(c.estimable(), "two sources spoke and netted out — the dispersion IS estimable");
    }

    @Test
    void twoSourcesAgreeingRemainEstimable() {
        var c = ForecastCombiner.combine("AAPL",
                List.of(new ForecastCombiner.Weighted(Forecast.of("trend", "AAPL", 10.0), 1.0),
                        new ForecastCombiner.Weighted(Forecast.of("reversion", "AAPL", 10.0), 1.0)), 0.5);
        assertEquals(1.0, c.agreement(), 1e-12);
        assertTrue(c.estimable(), "the ordinary corroborated path is untouched");
    }

    @Test
    void noSourceAtAllStaysEstimableSoAdr0065StillSweeps() {
        var c = ForecastCombiner.combine("SAP", List.of(), 0.5);
        assertEquals(0, c.activeSources());
        assertTrue(c.estimable(),
                "no view exists, so there is nothing whose uncertainty is unknown — ADR-0065 owns this case");
    }

    // ---- the planner: what the desk does about it ----

    @Test
    void aHeldNameOnOneSourceIsHeldNotLiquidated() {
        // The live GOOG exit, reproduced. Short 19 at 364.58, called by `xsreversion` alone.
        // Before ADR-0135: target 0 ⇒ gap +19 against a band of |0|×0.2 = 0 ⇒ ADR-0090 works the
        // reduction in FULL ⇒ BUY 19, exactly the order the tape recorded.
        var targets = FusionPlanner.plan(
                views(Forecast.of("xsreversion", "GOOG", -10.969915627928405)),
                List.of("GOOG"), s -> 0.3762324120944941,
                id -> new BigDecimal("364.58"), id -> BigDecimal.ONE, id -> BigDecimal.valueOf(-19), PLAN);

        assertEquals(1, targets.size());
        FusionPlanner.Target t = targets.get(0);
        assertEquals(1, t.sources());
        assertFalse(t.estimable(), "surfaced on the target, so /api/fusion/targets shows WHY it held");
        assertEquals(0, BigDecimal.valueOf(-19).compareTo(t.targetQty()),
                "the target IS the inventory already held — the desk asks for no change");
        assertEquals(0, BigDecimal.ZERO.compareTo(t.deltaQty()), "and therefore trades nothing");
    }

    @Test
    void aFlatNameOnOneSourceStillDoesNotOpen() {
        // The rule is strictly one-way: an unestimable view never SIZES a position either. Holding is
        // the absence of a trade, not a licence to put risk on without corroboration (ADR-0124).
        var targets = FusionPlanner.plan(
                views(Forecast.of("xsreversion", "NVDA", 10.218541558741356)),
                List.of(), s -> 0.3762324120944941,
                id -> new BigDecimal("197.37"), id -> BigDecimal.ONE, id -> BigDecimal.ZERO, PLAN);

        FusionPlanner.Target t = targets.get(0);
        assertFalse(t.estimable());
        assertEquals(0, BigDecimal.ZERO.compareTo(t.targetQty()));
        assertEquals(0, BigDecimal.ZERO.compareTo(t.deltaQty()), "no corroboration, no new risk");
    }

    @Test
    void aMeasuredViewOfFlatStillExitsInFull() {
        // The regression guard that keeps this change narrow: when sources actually speak and net out,
        // the exit is byte-identical to the pre-ADR-0135 desk.
        var targets = FusionPlanner.plan(
                views(Forecast.of("trend", "MSFT", 12.0), Forecast.of("reversion", "MSFT", -12.0)),
                List.of("MSFT"), s -> 1.0,
                id -> new BigDecimal("473.33"), id -> BigDecimal.ONE, id -> BigDecimal.valueOf(11), PLAN);

        FusionPlanner.Target t = targets.get(0);
        assertTrue(t.estimable());
        assertEquals(0, t.targetQty().signum(), "a measured view of flat is still a target of flat");
        assertEquals(qty("-11"), t.deltaQty(), "and is still worked in full (ADR-0090)");
    }

    @Test
    void aHeldNameWithNoViewAtAllIsStillSweptToFlat() {
        // ADR-0065's orphan sweep is deliberately NOT in scope: silence with no source at all still
        // works the position down, exactly as OrphanedPositionTest asserts.
        var targets = FusionPlanner.plan(Map.of(), List.of("GOOG"), s -> 1.0,
                id -> BigDecimal.valueOf(178), id -> BigDecimal.ONE, id -> BigDecimal.valueOf(131), PLAN);
        assertTrue(targets.get(0).estimable());
        assertEquals(qty("-131"), targets.get(0).deltaQty());
    }

    @Test
    void theTrailingCutCanStillFlattenAnUnestimableHold() {
        // The safety floor is what exits WITHOUT a view, and it runs downstream of the planner against
        // whatever target the planner produced. A held-because-unestimable target must not be immune.
        var held = FusionPlanner.plan(
                views(Forecast.of("xsreversion", "GOOG", -10.969915627928405)),
                List.of("GOOG"), s -> 0.3762324120944941,
                id -> new BigDecimal("364.58"), id -> BigDecimal.ONE, id -> BigDecimal.valueOf(-19), PLAN);
        FusionPlanner.Target t = held.get(0);
        // The cut's projection is pure quantity arithmetic on the CURRENT position, independent of the
        // target the planner asked for, so the exit it plans is the whole position either way.
        assertEquals(qty("19"), TargetPlanner.orderDelta(BigDecimal.ZERO, t.currentQty(), 0.2, 0.5),
                "a cut to flat still buys back the whole short");
    }
}
