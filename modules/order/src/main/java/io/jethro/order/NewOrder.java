package io.jethro.order;

import io.jethro.domain.OrderType;
import io.jethro.domain.Side;
import io.jethro.domain.TimeInForce;

import java.math.BigDecimal;

/** A request to open an order. idempotencyKey makes re-submits safe (invariant 6).
 *  {@code timeInForce} defaults to GTC (ADR-0025). */
public record NewOrder(
        String idempotencyKey,
        String bookId,
        String instrumentId,
        Side side,
        OrderType type,
        BigDecimal quantity,
        BigDecimal limitPrice,
        TimeInForce timeInForce) {

    public NewOrder {
        if (timeInForce == null) {
            timeInForce = TimeInForce.GTC;
        }
    }

    /** Convenience: GTC — the default and the only pre-ADR-0025 behaviour. */
    public NewOrder(String idempotencyKey, String bookId, String instrumentId, Side side,
                    OrderType type, BigDecimal quantity, BigDecimal limitPrice) {
        this(idempotencyKey, bookId, instrumentId, side, type, quantity, limitPrice, TimeInForce.GTC);
    }
}
