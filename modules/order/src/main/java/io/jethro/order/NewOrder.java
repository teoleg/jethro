package io.jethro.order;

import io.jethro.domain.OrderType;
import io.jethro.domain.Side;
import io.jethro.domain.TimeInForce;

import java.math.BigDecimal;

/** A request to open an order. idempotencyKey makes re-submits safe (invariant 6).
 *  {@code timeInForce} defaults to GTC (ADR-0025).
 *
 *  <p>{@code originReason} (ADR-0134) is the ORIGINATION trigger — why the desk wanted this trade,
 *  in the words of the caller that decided to place it. It is persisted at insert and never
 *  overwritten by a later status transition, which is what makes an order that FILLED explicable;
 *  {@code Order.reason} answers the different question of why a STATUS changed and so is populated
 *  only on the failure branches. Truncated to the column width at the boundary, and nullable — a
 *  missing origin degrades the post-mortem, it must never block an order. */
public record NewOrder(
        String idempotencyKey,
        String bookId,
        String instrumentId,
        Side side,
        OrderType type,
        BigDecimal quantity,
        BigDecimal limitPrice,
        TimeInForce timeInForce,
        String originReason) {

    /** Matches {@code orders.origin_reason varchar(256)} (V50) — longer triggers are truncated, never rejected. */
    public static final int MAX_ORIGIN_REASON = 256;

    public NewOrder {
        if (timeInForce == null) {
            timeInForce = TimeInForce.GTC;
        }
        if (originReason != null) {
            originReason = originReason.isBlank() ? null
                    : originReason.length() > MAX_ORIGIN_REASON
                            ? originReason.substring(0, MAX_ORIGIN_REASON) : originReason;
        }
    }

    /** Without an origin — the pre-ADR-0134 shape, kept for callers that have no trigger to state. */
    public NewOrder(String idempotencyKey, String bookId, String instrumentId, Side side,
                    OrderType type, BigDecimal quantity, BigDecimal limitPrice,
                    TimeInForce timeInForce) {
        this(idempotencyKey, bookId, instrumentId, side, type, quantity, limitPrice, timeInForce, null);
    }

    /** Convenience: GTC — the default and the only pre-ADR-0025 behaviour. */
    public NewOrder(String idempotencyKey, String bookId, String instrumentId, Side side,
                    OrderType type, BigDecimal quantity, BigDecimal limitPrice) {
        this(idempotencyKey, bookId, instrumentId, side, type, quantity, limitPrice, TimeInForce.GTC, null);
    }

    /** GTC, with the originating trigger stated (ADR-0134). */
    public NewOrder(String idempotencyKey, String bookId, String instrumentId, Side side,
                    OrderType type, BigDecimal quantity, BigDecimal limitPrice, String originReason) {
        this(idempotencyKey, bookId, instrumentId, side, type, quantity, limitPrice,
                TimeInForce.GTC, originReason);
    }
}
