package io.jethro.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/**
 * An order owned by a book (ADR-0008). {@code idempotencyKey} implements invariant 6
 * on the order path: re-submitting the same command must not create a second order.
 * {@code timeInForce} (ADR-0025): GTC works until filled/cancelled; IOC cancels on
 * arrival if not marketable; DAY expires at the session close.
 */
public record Order(
        String orderId,
        String idempotencyKey,
        BookId bookId,
        InstrumentId instrumentId,
        Side side,
        OrderType type,
        BigDecimal quantity,
        Optional<BigDecimal> limitPrice,
        TimeInForce timeInForce,
        OrderStatus status,
        Instant createdAt) {

    public Order {
        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("order quantity must be positive");
        }
        if (type == OrderType.LIMIT && limitPrice.isEmpty()) {
            throw new IllegalArgumentException("LIMIT order requires a limitPrice");
        }
        if (timeInForce == null) {
            throw new IllegalArgumentException("timeInForce is required");
        }
    }

    public Order withStatus(OrderStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("illegal order transition " + status + " -> " + next);
        }
        return new Order(orderId, idempotencyKey, bookId, instrumentId, side, type,
                quantity, limitPrice, timeInForce, next, createdAt);
    }
}
