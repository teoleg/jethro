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
 *       unitNotional / (price × contractMultiplier)}. {@code unitNotional} is the deterministic
 *       cash-at-risk per name at a typical forecast; a cap-strength forecast (±20) targets 2× it.
 *       The divisor is the money value of ONE unit of the instrument, which is `price` only for a
 *       name quoted per unit of currency (equities, FX) — see {@link #targetQuantity} (ADR-0078).</li>
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

    /**
     * Signed target quantity for a combined forecast, sized in the instrument's OWN contract terms
     * (ADR-0078); 0 when the forecast is 0 or the contract cannot be valued.
     *
     * <p><b>Why the multiplier is not optional.</b> {@code unitNotional} is cash — the economic exposure
     * the desk wants in this name — so turning it into a quantity requires dividing by the money value of
     * one unit of the instrument. That is {@code price} only for a name quoted per unit of currency (an
     * equity share, a unit of foreign currency, both of which carry multiplier 1). For a future, a bond
     * future or a swap it is {@code price × contractMultiplier}, exactly as {@code PositionRisk} values the
     * resulting position ({@code netExposure = quantity · mark · multiplier}) and as {@code Positions}
     * settles its PnL. Dividing by price alone therefore does not produce a smaller-or-larger position, it
     * produces a position whose exposure is {@code multiplier ×} the cash that was asked for — on this
     * universe 50× on ES, 20× on NQ, 1000× on the note futures and 45,000–80,000× on the swaps. The
     * ADR-0039 hedge advisor has always divided by {@code price × multiplier}; this is the same arithmetic
     * on the other order path.
     *
     * @param contractMultiplier currency value of one point of price movement per unit of quantity, from
     *                           the instrument master. Null or non-positive means the contract spec is
     *                           unknown, and a size cannot be asserted without inventing one
     *                           (ADR-0016 / invariant 7): the target is ZERO, which is reduce-only by
     *                           construction and matches the executor's "not in the instrument master"
     *                           veto rather than silently sizing as if it were a share.
     */
    public static BigDecimal targetQuantity(double combinedForecast, BigDecimal unitNotional,
                                            BigDecimal price, BigDecimal contractMultiplier) {
        if (unitNotional == null || price == null || price.signum() <= 0 || combinedForecast == 0.0
                || contractMultiplier == null || contractMultiplier.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal fraction = BigDecimal.valueOf(combinedForecast / Forecast.TARGET_ABS); // ±2 at the cap
        BigDecimal unitValue = price.multiply(contractMultiplier); // money per 1 unit of quantity
        return unitNotional.multiply(fraction).divide(unitValue, QTY_SCALE, RoundingMode.HALF_EVEN);
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
     * The order quantity to actually submit for {@code delta}, rounded in the instrument's own contract
     * terms (ADR-0078). Always rounds TOWARD ZERO, so rounding can only ever trade less than planned.
     *
     * <p>A name quoted per unit of currency (multiplier 1 — every equity and FX pair here) rounds to a
     * whole unit exactly as before: a fractional share is not a thing the desk trades, and the
     * dust-suppression that rounding provides is unchanged. A name whose unit is a CONTRACT does not:
     * one ES contract is worth {@code price × 50}, so a correctly-sized position in it is routinely a
     * fraction of a contract, and rounding that to a whole number is not conservative — it is the
     * difference between the exposure asked for and 5× it, or nothing at all. Those names keep the
     * quantity scale the position, fill and order records already carry, which is exactly what the
     * ADR-0039 hedge advisor has always submitted on ES. No minimum-size dial is introduced: the
     * ADR-0055 no-trade band already suppresses small corrections, and inventing a minimum notional
     * would be a money number without provenance (ADR-0016 / invariant 7).
     */
    public static BigDecimal tradableQuantity(BigDecimal delta, BigDecimal contractMultiplier) {
        BigDecimal d = delta == null ? BigDecimal.ZERO : delta;
        boolean quotedPerUnit = contractMultiplier != null
                && contractMultiplier.compareTo(BigDecimal.ONE) == 0;
        return quotedPerUnit
                ? d.setScale(0, RoundingMode.DOWN)
                : d.setScale(QTY_SCALE, RoundingMode.DOWN);
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

    /**
     * Does trading {@code delta} leave the book with LESS absolute exposure in this name than it has
     * now? Pure sign/magnitude arithmetic: {@code |current + delta| < |current|}.
     *
     * <p>Used to decide which controls a delta must clear. A control whose purpose is "is this view
     * worth putting risk on?" — the ADR-0059 conviction floor, the ADR-0049 backtest-support gate —
     * has nothing to say about taking risk OFF, and applying it there does not make the desk safer, it
     * traps it: the position whose signal has gone silent is exactly the one those gates would refuse
     * to let go of. Note a sign flip that overshoots (long 100 → short 300) is NOT reducing by this
     * test, so a flip must still earn its conviction the ordinary way.
     */
    public static boolean isRiskReducing(BigDecimal delta, BigDecimal current) {
        BigDecimal d = delta == null ? BigDecimal.ZERO : delta;
        BigDecimal cur = current == null ? BigDecimal.ZERO : current;
        if (d.signum() == 0 || cur.signum() == 0) {
            return false; // nothing traded, or nothing held — opening is never a reduction
        }
        return cur.add(d).abs().compareTo(cur.abs()) < 0;
    }
}
