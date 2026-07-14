package io.jethro.order;

import io.jethro.domain.Fill;
import io.jethro.domain.Order;
import io.jethro.domain.OrderType;
import io.jethro.domain.Side;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Simulated execution with realistic costs (ADR-0025). The mark is treated as the mid; a
 * marketable order crosses the half-spread and a MARKET order additionally pays the fee,
 * both embedded in the fill price (exact decimals, invariant 1):
 *
 * <pre>
 *   touch(BUY)  = mid × (1 + spread/2·10⁻⁴)      price-quoted (equity/future/FX)
 *   touch(SELL) = mid × (1 − spread/2·10⁻⁴)
 *   touch(SWAP) = mid ± spread/2 · 0.01           rate-quoted: additive in rate bp
 *   MARKET fill = touch × (1 ± fee·10⁻⁴)          fee only on MARKET (a LIMIT must never
 *                                                 fill beyond its limit price — fee as a
 *                                                 separate cash line is the follow-up)
 * </pre>
 *
 * Worked example (finance-math rule): BUY MARKET 131 AAPL, mid 190.00, EQUITY spread 5bp
 * fee 1bp → touch 190 × 1.00025 = 190.0475, fill 190.0475 × 1.0001 = <b>190.066505</b>;
 * the round-trip cost vs mid is ≈ 190 × (5+2)/10⁴ ≈ $0.133/share — churn is no longer free.
 *
 * <p>LIMIT fills at the limit price when the <em>touch</em> (not the mid) crosses it — you
 * cannot buy at your limit until the ask reaches it, which is how real books behave.
 * No partial fills yet (noted, not hidden).
 */
public final class SimulatedExecutor {

    private static final int PRICE_SCALE = 6;
    private static final RoundingMode ROUND = RoundingMode.HALF_EVEN;

    private final ExecutionCostSource costs;
    private final BigDecimal maxAdvParticipation; // nullable → participation cap off

    public SimulatedExecutor(ExecutionCostSource costs) {
        this(costs, null);
    }

    /** @param maxAdvParticipation reject orders whose notional exceeds this fraction of the
     *                             instrument's ADV (e.g. 0.02 = 2%); null disables the cap. */
    public SimulatedExecutor(ExecutionCostSource costs, BigDecimal maxAdvParticipation) {
        this.costs = costs;
        this.maxAdvParticipation = maxAdvParticipation;
    }

    /**
     * ADV participation gate (ADR-0025): an order that would be more than the configured
     * fraction of the instrument's average daily volume is rejected pre-route — the impact
     * model's square-root law is calibrated for small participations and a desk wouldn't
     * slam 2%+ of ADV as one MARKET order anyway. Empty = fine to route. No ADV on file →
     * no cap (unmodelled, disclosed).
     */
    public Optional<String> participationRejection(Order order, BigDecimal mid) {
        if (maxAdvParticipation == null || mid == null) {
            return Optional.empty();
        }
        ExecutionCostSource.Cost cost = costs.costFor(order.instrumentId().value());
        if (cost.advUsd() == null || cost.advUsd().signum() <= 0 || cost.rateQuoted()) {
            return Optional.empty();
        }
        BigDecimal multiplier = cost.multiplier() != null ? cost.multiplier() : BigDecimal.ONE;
        BigDecimal notional = order.quantity().multiply(mid).multiply(multiplier).abs();
        BigDecimal cap = cost.advUsd().multiply(maxAdvParticipation);
        if (notional.compareTo(cap) > 0) {
            return Optional.of("order notional " + notional.toPlainString() + " exceeds "
                    + maxAdvParticipation.movePointRight(2).toPlainString() + "% of ADV ("
                    + cost.advUsd().toPlainString() + ") — split the order or reduce size");
        }
        return Optional.empty();
    }

    /** Attempts to execute at the mid alone (no quote data — synthetic spread). */
    public Optional<Fill> tryExecute(Order order, BigDecimal mid) {
        return tryExecute(order, mid, null, null);
    }

    /**
     * Attempts to execute an order. When a REAL top-of-book quote rides with the mark
     * (ADR-0025: the sim publishes bid/ask synthesized from the same spread config), the
     * marketable side IS the quoted touch — BUY crosses to the ask, SELL hits the bid; the
     * synthetic mid±half-spread only remains as the fallback for feeds without quote data.
     * Empty = cannot fill now.
     */
    public Optional<Fill> tryExecute(Order order, BigDecimal mid, BigDecimal bid, BigDecimal ask) {
        if (mid == null) {
            return Optional.empty(); // no market data — caller rejects
        }
        ExecutionCostSource.Cost cost = costs.costFor(order.instrumentId().value());
        BigDecimal quoted = order.side() == Side.BUY ? ask : bid;
        BigDecimal touch = quoted != null && quoted.signum() > 0 ? quoted : touch(order.side(), mid, cost);
        BigDecimal fillPrice = switch (order.type()) {
            case MARKET -> withImpact(order, mid, withFee(order.side(), touch, cost.feeBps()), cost);
            case LIMIT -> marketable(order, touch) ? order.limitPrice().orElseThrow() : null;
        };
        if (fillPrice == null) {
            return Optional.empty(); // limit not marketable — stays working
        }
        return Optional.of(new Fill(
                "fill-" + UUID.randomUUID(),
                order.orderId(),
                order.bookId(),
                order.instrumentId(),
                order.side(),
                order.quantity(),
                fillPrice.setScale(PRICE_SCALE, ROUND),
                Instant.now()));
    }

    /** The side of the spread a marketable order crosses: mid ± half-spread. */
    private static BigDecimal touch(Side side, BigDecimal mid, ExecutionCostSource.Cost cost) {
        if (cost.spreadBps().signum() == 0) {
            return mid;
        }
        if (cost.rateQuoted()) {
            // Rate quote (percent): 1bp of rate = 0.01 quote units; half the spread, additive.
            BigDecimal delta = cost.spreadBps().movePointLeft(2).divide(BigDecimal.TWO); // bps·0.01/2
            return side == Side.BUY ? mid.add(delta) : mid.subtract(delta);
        }
        // Price quote: half the spread as a fraction of price — (bps·10⁻⁴)/2. Division by 2
        // is exact for decimals (finance-math rule: no hidden rounding mid-calculation).
        BigDecimal factor = cost.spreadBps().movePointLeft(4).divide(BigDecimal.TWO);
        return side == Side.BUY
                ? mid.multiply(BigDecimal.ONE.add(factor))
                : mid.multiply(BigDecimal.ONE.subtract(factor));
    }

    /** MARKET orders additionally pay the fee, embedded in the price (v1, ADR-0025). */
    private static BigDecimal withFee(Side side, BigDecimal touch, BigDecimal feeBps) {
        if (feeBps.signum() == 0) {
            return touch;
        }
        BigDecimal factor = feeBps.movePointLeft(4);
        return side == Side.BUY
                ? touch.multiply(BigDecimal.ONE.add(factor))
                : touch.multiply(BigDecimal.ONE.subtract(factor));
    }

    /**
     * Square-root market impact on MARKET fills (ADR-0025 follow-up): the standard empirical
     * law — impact fraction = σ_daily × √(orderNotional / ADV) — applied adversely to the
     * price like the fee. Worked: SELL 200 AAPL @ ~190 → notional 38,000; ADV $12B; σ 1.8%/day
     * → impact = 0.018 × √(38,000/12e9) = 0.018 × 0.00178 ≈ 0.32bp — small at demo sizes,
     * grows with the square root, which is exactly the point. Needs ADV AND measured vol AND
     * a price quote; anything missing → no impact (unmodelled, disclosed, never guessed).
     * The coefficient (Y=1) is the conventional order-of-magnitude choice, stated not fitted.
     */
    private static BigDecimal withImpact(Order order, BigDecimal mid, BigDecimal price,
                                         ExecutionCostSource.Cost cost) {
        if (cost.advUsd() == null || cost.advUsd().signum() <= 0
                || cost.dailyVol() == null || cost.dailyVol().signum() <= 0 || cost.rateQuoted()) {
            return price;
        }
        BigDecimal multiplier = cost.multiplier() != null ? cost.multiplier() : BigDecimal.ONE;
        double notional = order.quantity().multiply(mid).multiply(multiplier).abs().doubleValue();
        double participation = notional / cost.advUsd().doubleValue();
        // Impact coefficient is analytics (double); it becomes money only multiplied into the
        // exact price below (same boundary convention as duration/vol).
        BigDecimal impact = BigDecimal.valueOf(cost.dailyVol().doubleValue() * Math.sqrt(participation));
        return order.side() == Side.BUY
                ? price.multiply(BigDecimal.ONE.add(impact))
                : price.multiply(BigDecimal.ONE.subtract(impact));
    }

    private static boolean marketable(Order order, BigDecimal touch) {
        BigDecimal limit = order.limitPrice().orElseThrow();
        // BUY fills when the ask is at or below the limit; SELL when the bid is at or above.
        return order.side() == Side.BUY
                ? touch.compareTo(limit) <= 0
                : touch.compareTo(limit) >= 0;
    }

    /** Guard so callers don't construct MARKET orders without a mark path. */
    public static boolean requiresMark(OrderType type) {
        return type == OrderType.MARKET;
    }
}
