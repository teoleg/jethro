package io.jethro.app.hypothesis;

import java.math.BigDecimal;

/**
 * A durable record of an AI hypothesis the bounded-autonomy envelope executed (ADR-0022) —
 * one per order the model's thesis led to. Persisted (survives restart) and shown as a sticky
 * list in the UI, so an executed decision never scrolls off with the next generation cycle.
 * Quantity is exact decimal (invariant 1), never a float.
 */
public record HypothesisRecord(String id, long timestampMillis, String instrumentId, String direction,
                               String horizon, String conviction, String thesis, String book,
                               BigDecimal quantity, Boolean backtestSupported,
                               String orderId, String orderStatus) {
}
