package io.jethro.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * An execution against an order. Fills are the source of truth for positions
 * (invariant 3); {@code fillId} is the dedupe key under at-least-once delivery
 * (invariant 6). Quantity is always positive; direction comes from {@code side}.
 * {@code fee} is the commission as a SEPARATE cash cost (ADR-0025): the price is the
 * traded price — fees never contaminate it (a LIMIT fill can now carry its fee without
 * violating the limit-price contract), and the ledger books the fee as realized cost.
 */
public record Fill(
        String fillId,
        String orderId,
        BookId bookId,
        InstrumentId instrumentId,
        Side side,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal fee,
        Instant executedAt) {

    /** Fee-free fill (tests / venues without commission). */
    public Fill(String fillId, String orderId, BookId bookId, InstrumentId instrumentId,
                Side side, BigDecimal quantity, BigDecimal price, Instant executedAt) {
        this(fillId, orderId, bookId, instrumentId, side, quantity, price, BigDecimal.ZERO, executedAt);
    }

    public Fill {
        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("fill quantity must be positive");
        }
        if (price == null || price.signum() < 0) {
            throw new IllegalArgumentException("fill price must be non-negative");
        }
        if (fee == null || fee.signum() < 0) {
            throw new IllegalArgumentException("fill fee must be non-negative");
        }
    }

    public BigDecimal signedQuantity() {
        return side.signed(quantity);
    }
}
