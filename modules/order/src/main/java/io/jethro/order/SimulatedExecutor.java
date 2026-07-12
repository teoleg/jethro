package io.jethro.order;

import io.jethro.domain.Fill;
import io.jethro.domain.Order;
import io.jethro.domain.OrderType;
import io.jethro.domain.Side;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Simulated execution until a real broker is wired (ADR-0003). Deterministic given a
 * price: MARKET fills the full quantity at the current mark; LIMIT fills at the limit
 * price only when the mark is marketable (crossed), otherwise the order stays working.
 * No real venue, no partial fills yet.
 */
public final class SimulatedExecutor {

    /** Attempts to execute an order at the given mark. Empty = cannot fill now. */
    public Optional<Fill> tryExecute(Order order, BigDecimal mark) {
        if (mark == null) {
            return Optional.empty(); // no market data — caller rejects
        }
        BigDecimal fillPrice = switch (order.type()) {
            case MARKET -> mark;
            case LIMIT -> marketable(order, mark) ? order.limitPrice().orElseThrow() : null;
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
                fillPrice,
                Instant.now()));
    }

    private static boolean marketable(Order order, BigDecimal mark) {
        BigDecimal limit = order.limitPrice().orElseThrow();
        // BUY fills when the market is at or below the limit; SELL at or above.
        return order.side() == Side.BUY
                ? mark.compareTo(limit) <= 0
                : mark.compareTo(limit) >= 0;
    }

    /** Guard so callers don't construct MARKET orders without a mark path. */
    public static boolean requiresMark(OrderType type) {
        return type == OrderType.MARKET;
    }
}
