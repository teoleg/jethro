package io.jethro.order;

import io.jethro.domain.OrderType;
import io.jethro.domain.Side;

import java.math.BigDecimal;

/** A request to open an order. idempotencyKey makes re-submits safe (invariant 6). */
public record NewOrder(
        String idempotencyKey,
        String bookId,
        String instrumentId,
        Side side,
        OrderType type,
        BigDecimal quantity,
        BigDecimal limitPrice) {
}
