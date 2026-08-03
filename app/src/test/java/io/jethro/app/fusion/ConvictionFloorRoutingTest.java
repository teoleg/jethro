package io.jethro.app.fusion;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0136 — below the conviction floor a name has two states, HELD or FLAT, and is never re-sized.
 *
 * <p>The defect: ADR-0065 waived the ADR-0059 floor for every risk-REDUCING delta so a decayed view
 * could not trap a position. That waiver is right for the exit and wrong for a partial rebalance, so
 * a name whose forecast had decayed below the floor was still walked toward a target computed from
 * that sub-floor forecast, every cycle, paying fee and spread each time.
 *
 * <p>The observed instance the rule is written against (read from {@code recent_orders}, 2026-08-03):
 * BAC short, six separate {@code BUY 1} fills tagged "fusion reduce toward a smaller target" at
 * forecasts 0.347, 0.536, 0.702, 2.836, 1.720, 1.827 — every one below the 5.0 floor, every one a
 * partial reduce that left the short open. Exact decimal on the position arithmetic (invariant 1).
 */
class ConvictionFloorRoutingTest {

    private static final double FLOOR = 5.0;

    private static BigDecimal qty(String v) {
        return new BigDecimal(v).setScale(6);
    }

    /** A target carrying only the fields the routing gate reads; the rest are structurally irrelevant. */
    private static FusionPlanner.Target target(double forecast, String currentQty, String deltaQty) {
        return new FusionPlanner.Target("BAC", forecast, 3, 1.0, 1.0,
                BigDecimal.valueOf(62), BigDecimal.ZERO, qty(currentQty), qty(deltaQty), List.of());
    }

    private static boolean routes(double forecast, String currentQty, String deltaQty) {
        FusionPlanner.Target t = target(forecast, currentQty, deltaQty);
        boolean reducing = TargetPlanner.isRiskReducing(t.deltaQty(), t.currentQty());
        return FusionLifecycle.clearsConvictionFloor(t, reducing, FLOOR);
    }

    @Test
    void theSixBacDribbleOrdersAreAllSuppressed() {
        // Short 288; each order buys 1 back, leaving the short open. The forecast is RISING across
        // them (0.347 → 2.836) and never once reaches the floor the desk uses to open a position.
        for (double forecast : new double[] {0.3474462086964614, 0.5363969925205933, 0.7016479414426884,
                2.835935684486436, 1.72006937226672, 1.82687778188073}) {
            assertFalse(routes(forecast, "-288", "1"),
                    "a partial reduce on a sub-floor view must not be routed: forecast=" + forecast);
        }
    }

    @Test
    void theFullExitIsNeverBlockedHoweverWeakTheView() {
        // BAC BUY 435 closing a 435 short at forecast 0.0 — the breadth-collapse flatten. This is the
        // case ADR-0065's waiver exists for and it must survive unchanged, at any forecast.
        assertTrue(routes(0.0, "-435", "435"), "an exit to exactly flat routes at a zero forecast");
        assertTrue(routes(-0.0, "26", "-26"), "sign of zero is irrelevant");
        assertTrue(routes(4.999, "131", "-131"), "just below the floor is still an exit");
        assertTrue(routes(0.0, "-0.5", "0.5"), "a fractional position closes too");
    }

    @Test
    void aStrongViewRoutesExactlyAsBefore() {
        assertTrue(routes(5.968520080242135, "0", "26"), "entry from flat at a clearing forecast");
        assertTrue(routes(-9.828135478631083, "0", "-117"), "short entry from flat");
        assertTrue(routes(5.0, "0", "1"), "the floor is inclusive, as it always was");
        assertTrue(routes(-13.5, "288", "-40"), "a partial reduce ABOVE the floor is untouched");
    }

    @Test
    void aSubFloorEntryIsBlockedExactlyAsBefore() {
        // Unchanged behaviour: this is the branch ADR-0059 always covered.
        assertFalse(routes(2.0, "0", "10"), "opening from flat below the floor");
        assertFalse(routes(4.999, "100", "20"), "adding to a long below the floor");
    }

    @Test
    void aFlipThroughFlatIsNotAnExit() {
        // long 131 → short 269 is not risk-reducing (it is a bigger position on the other side), so it
        // is gated as the entry it is. The waiver must not leak to it via the arithmetic.
        assertFalse(routes(1.5, "131", "-400"), "an overshooting flip on a sub-floor view is blocked");
        assertTrue(routes(12.0, "131", "-400"), "the same flip on a clearing view is untouched");
    }

    @Test
    void theRuleIsStrictlyOneWay() {
        // Every input either routes exactly as the old rule did, or is suppressed where the old rule
        // routed. It can never route something the old rule blocked.
        String[][] positions = {{"0", "10"}, {"0", "-10"}, {"131", "-131"}, {"131", "-40"},
                {"-288", "1"}, {"-288", "288"}, {"131", "-400"}, {"100", "20"}};
        for (double forecast : new double[] {0.0, 0.35, 2.0, 4.999, 5.0, 9.8, 15.0}) {
            for (String[] p : positions) {
                FusionPlanner.Target t = target(forecast, p[0], p[1]);
                boolean reducing = TargetPlanner.isRiskReducing(t.deltaQty(), t.currentQty());
                boolean before = reducing || Math.abs(forecast) >= FLOOR;   // the ADR-0065 rule
                boolean after = FusionLifecycle.clearsConvictionFloor(t, reducing, FLOOR);
                assertTrue(before || !after,
                        "must never route what the previous rule blocked: forecast=" + forecast
                                + " current=" + p[0] + " delta=" + p[1]);
            }
        }
    }
}
