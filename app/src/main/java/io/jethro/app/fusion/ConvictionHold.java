package io.jethro.app.fusion;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * ADR-0145 — the ADR-0059 conviction floor, applied to <b>both</b> sides of a position.
 *
 * <p><b>The asymmetry this closes.</b> The desk opens a name only when its combined forecast clears
 * {@code min-forecast-to-route}: "is this view strong enough to put risk ON?". Nothing asked the
 * mirror question. The planner's target is <em>linear</em> in the combined forecast
 * ({@code target = V·f/TARGET_ABS}, every step between them a scalar that does not depend on forecast
 * strength), so a forecast that merely DECAYS toward zero collapses the target toward zero, and the
 * whole position — bought minutes earlier at full conviction — is unwound at a forecast strength that
 * would not have been allowed to open a single share of it.
 *
 * <p>That is not a view changing its mind. {@code f ≈ 0} is the combiner saying it has <em>no view</em>,
 * and no view is a reason to HOLD, not a reason to liquidate. Under a forecast that mean-reverts on a
 * timescale shorter than the holding period, the pair "open at {@code |f| ≥ floor}" / "close at
 * {@code |f| ≈ 0}" is a round trip on every oscillation of a series the desk's own telemetry measures as
 * uninformative, and the desk pays the spread and the fee on both legs. Making the floor symmetric is
 * the standard Schmitt-trigger remedy — enter on conviction, exit on conviction, and do nothing in
 * between (the same hysteresis Carver's buffering applies to SIZE, applied here to the SIGNAL).
 *
 * <h3>What must still get through, untouched</h3>
 * A reduction has two possible authors and only one of them is the forecast:
 * <ul>
 *   <li>the <b>planner</b> — the forecast-implied target is already below the holding; and</li>
 *   <li>a <b>risk control</b> — ADR-0083's volatility budget, ADR-0079's normaliser, ADR-0104's book
 *       brake, ADR-0137's gross cap, ADR-0064's edge gate, ADR-0086's trailing cut — each of which
 *       shrinks the planner's target further.</li>
 * </ul>
 * Only the first is gated. The second is separated out exactly, by measuring both targets on the side
 * of flat the holding is on. Write {@code s = sgn(held)}, {@code h = |held|}, {@code p = s·planned}
 * (the planner's target before any control) and {@code c = s·controlled} (the target the controls
 * actually left):
 * <pre>
 *   destination the planner alone asks for   min(h, p)
 *   destination the controls impose          min(h, c)
 *   reduction the CONTROLS author            max(0, min(h, p) − min(h, c))     ← always allowed
 *   reduction the FORECAST authors           h − min(h, p)                     ← needs conviction
 * </pre>
 * With no conviction the order is capped at the control-authored part. Every control therefore keeps
 * its exact effect: it is subtracted from the planner's own destination, not from the holding, so a
 * control that halves the book still routes the whole half. A control that ordered the exit outright
 * ({@code controlled == 0} — the ADR-0086 chandelier cut, the ADR-0065 orphan unwind, the ADR-0027
 * breaker above them) returns before any of this and is never held at all.
 *
 * <h3>What it can never do</h3>
 * {@code |delta'| ≤ |delta|} and {@code sgn(delta') ∈ {0, sgn(delta)}} by construction, and it fires
 * only on a delta that {@link TargetPlanner#isRiskReducing} already classified as risk-REDUCING. So it
 * never opens a position, never enlarges one, never flips one, never speeds a trade up and never
 * touches an increase. Its only effect is that the desk keeps risk it was about to shed for no stated
 * reason — which is the ADR-0132 objective, not a relaxation of any floor: the pre-trade guardrail, the
 * firm drawdown breaker and every control above still have the last word on the order that does route.
 *
 * <p>It introduces <b>no number</b>. The threshold is {@code jethro.fusion.min-forecast-to-route}, the
 * ADR-0059 floor the desk already applies to entries; setting that floor to zero disables both halves
 * together (invariant 7 / ADR-0016 — nothing here is self-chosen). Exact decimal throughout
 * (invariant 1); the only doubles are the dimensionless forecast and floor.
 */
final class ConvictionHold {

    /** The scale every quantity here is stated at — the scale positions, orders and fills already use. */
    private static final int QTY_SCALE = 6;

    private ConvictionHold() {
    }

    /**
     * The reducing order the desk may route when its forecast carries no conviction: the part of it a
     * risk control authored, and nothing more.
     *
     * @param delta              the reducing order the buffer produced
     * @param held               the position now
     * @param planned            the PLANNER's target for this name, before any risk control ran; null
     *                           means unwired, which leaves the delta untouched
     * @param controlled         the target the controls left — flat means a control ordered the exit
     * @param forecast           this cycle's combined forecast for the name
     * @param minForecastToRoute the ADR-0059 conviction floor; at or below zero the floor is off
     */
    static BigDecimal apply(BigDecimal delta, BigDecimal held, BigDecimal planned, BigDecimal controlled,
                            double forecast, double minForecastToRoute) {
        if (planned == null || !(minForecastToRoute > 0.0)) {
            return delta; // unwired, or the desk has no conviction floor at all
        }
        if (controlled == null || controlled.signum() == 0) {
            return delta; // a control ordered the exit — never buffered, never held (ADR-0090/0086/0065)
        }
        if (Math.abs(forecast) >= minForecastToRoute) {
            return delta; // the view that asks for this reduction would have been allowed to open it
        }
        if (!TargetPlanner.isRiskReducing(delta, held)) {
            return delta; // an increase, or nothing traded — this is not the question being asked
        }
        int side = held.signum(); // non-zero: isRiskReducing is false against a flat book
        BigDecimal h = held.abs();
        BigDecimal p = onHeldSide(planned, side);
        BigDecimal c = onHeldSide(controlled, side);
        BigDecimal authoredByControls = h.min(p).subtract(h.min(c)).max(BigDecimal.ZERO);
        BigDecimal magnitude = delta.abs().min(authoredByControls);
        BigDecimal capped = side > 0 ? magnitude.negate() : magnitude;
        return capped.setScale(QTY_SCALE, RoundingMode.HALF_EVEN);
    }

    /** A target restated on the side of flat the holding is on: positive means "more of what is held". */
    private static BigDecimal onHeldSide(BigDecimal target, int side) {
        return side > 0 ? target : target.negate();
    }
}
