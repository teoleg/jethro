package io.jethro.domain;

import java.math.BigDecimal;

/**
 * A position keyed (bookId, instrumentId) — a projection of fills (invariant 3).
 * Sign convention: {@code quantity > 0} = long. When flat, {@code avgCost} is zero.
 */
public record Position(
        BookId bookId,
        InstrumentId instrumentId,
        BigDecimal quantity,
        BigDecimal avgCost) {

    public static Position flat(BookId bookId, InstrumentId instrumentId) {
        return new Position(bookId, instrumentId, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public boolean isFlat() {
        return quantity.signum() == 0;
    }
}
