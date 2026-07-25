package io.jethro.app.fusion;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Turns a combined forecast into a target position and a netted order delta (ADR-0055 phase 3). Two
 * deterministic steps, both invariant-1/7 clean (BigDecimal at this module boundary; no model number,
 * only the bounded forecast, enters — the arithmetic is fixed):
 *
 * <ol>
 *   <li><b>Target</b> (Carver vol-targeting shape): {@code target = (forecast / TARGET_ABS) ×
 *       unitNotional / price}. {@code unitNotional} is the deterministic cash-at-risk per name at a
 *       typical forecast; a cap-strength forecast (±20) targets 2× it.</li>
 *   <li><b>Partial adjustment</b> (Gârleanu-Pedersen 2013): never jump to the target — trade only a
 *       fraction toward it, and not at all inside a no-trade band around it. This is what makes the
 *       policy cost-aware: small frequent corrections are suppressed so we don't pay spread to chase
 *       noise. Because there is ONE combined target per instrument, the delta is inherently netted
 *       across every sleeve — no two subsystems trade the same name against each other.</li>
 * </ol>
 */
public final class TargetPlanner {

    private static final int QTY_SCALE = 6;

    private TargetPlanner() {
    }

    /** Signed target quantity for a combined forecast; 0 when price is non-positive or forecast is 0. */
    public static BigDecimal targetQuantity(double combinedForecast, BigDecimal unitNotional, BigDecimal price) {
        if (unitNotional == null || price == null || price.signum() <= 0 || combinedForecast == 0.0) {
            return BigDecimal.ZERO;
        }
        BigDecimal fraction = BigDecimal.valueOf(combinedForecast / Forecast.TARGET_ABS); // ±2 at the cap
        return unitNotional.multiply(fraction).divide(price, QTY_SCALE, RoundingMode.HALF_EVEN);
    }

    /**
     * The Gârleanu-Pedersen order delta: signed quantity to trade THIS cycle toward {@code target}.
     * Zero inside the no-trade band (|target − current| ≤ |target| × {@code bufferFraction}); otherwise
     * {@code adjustmentRate} of the gap. Exiting toward a zero target always trades (band collapses to 0).
     */
    public static BigDecimal orderDelta(BigDecimal target, BigDecimal current,
                                        double bufferFraction, double adjustmentRate) {
        BigDecimal tgt = target == null ? BigDecimal.ZERO : target;
        BigDecimal cur = current == null ? BigDecimal.ZERO : current;
        BigDecimal gap = tgt.subtract(cur);
        BigDecimal band = tgt.abs().multiply(BigDecimal.valueOf(Math.max(0, bufferFraction)));
        if (gap.abs().compareTo(band) <= 0) {
            return BigDecimal.ZERO; // inside the no-trade band — don't pay spread to chase noise
        }
        double rate = Math.max(0.0, Math.min(1.0, adjustmentRate));
        return gap.multiply(BigDecimal.valueOf(rate)).setScale(QTY_SCALE, RoundingMode.HALF_EVEN);
    }

    /**
     * The part of {@code delta} that does not INCREASE |position| — the reduce-only projection used
     * when the ADR-0064 edge gate finds no measured edge worth paying execution cost for.
     *
     * <p>Three cases, all pure arithmetic on the signs: flat already ⇒ trade nothing (opening is an
     * increase); same sign as the position ⇒ trade nothing (adding is an increase); opposite sign ⇒
     * trade at most enough to reach zero, never through it (crossing to the other side is a new
     * position, not a reduction). Cutting risk is always permitted — a gate that could trap the book
     * in a position it wants out of would be a worse bug than the churn it prevents.
     */
    public static BigDecimal reduceOnly(BigDecimal delta, BigDecimal current) {
        BigDecimal d = delta == null ? BigDecimal.ZERO : delta;
        BigDecimal cur = current == null ? BigDecimal.ZERO : current;
        if (d.signum() == 0 || cur.signum() == 0 || d.signum() == cur.signum()) {
            return BigDecimal.ZERO;
        }
        return d.abs().min(cur.abs()).multiply(BigDecimal.valueOf(d.signum()))
                .setScale(QTY_SCALE, RoundingMode.HALF_EVEN);
    }
}
