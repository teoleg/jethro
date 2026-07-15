package io.jethro.order;

import io.jethro.domain.Side;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Transaction-cost analysis math (ADR-0025): implementation-shortfall slippage of a fill
 * against the ARRIVAL price (the mid when the order was submitted — the decision price).
 * Sign convention: positive = cost, negative = price improvement.
 *
 * <pre>
 *   price-quoted: slippage_bps = dir × (fill − arrival) / arrival × 10⁴     dir = +1 BUY, −1 SELL
 *   rate-quoted:  slippage_bps = dir × (fill − arrival) × 100               (bp of RATE, additive)
 * </pre>
 *
 * Worked (finance-math rule): BUY MARKET arrival 190.00, fill 190.066505 (half-spread 2.5bp
 * + fee 1bp) → (0.066505/190)×10⁴ = <b>3.5003 bps</b> — TCA measures back exactly the
 * modelled cost on an immediate fill, and MORE when the market ran away first (that excess
 * is the delay cost a desk actually watches). SELL LIMIT above arrival measures negative —
 * a resting order that got its price is an improvement, not a cost.
 */
public final class Tca {

    private static final int BPS_SCALE = 4;
    private static final RoundingMode ROUND = RoundingMode.HALF_EVEN;

    private Tca() {
    }

    public static BigDecimal slippageBps(Side side, BigDecimal arrival, BigDecimal fill, boolean rateQuoted) {
        BigDecimal dir = side == Side.BUY ? BigDecimal.ONE : BigDecimal.ONE.negate();
        if (rateQuoted) {
            return fill.subtract(arrival).multiply(dir).movePointRight(2).setScale(BPS_SCALE, ROUND);
        }
        return fill.subtract(arrival)
                .divide(arrival, 12, ROUND)
                .multiply(dir)
                .movePointRight(4)
                .setScale(BPS_SCALE, ROUND);
    }
}
