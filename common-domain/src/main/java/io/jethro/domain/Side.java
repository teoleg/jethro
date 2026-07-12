package io.jethro.domain;

import java.math.BigDecimal;

/** Order/fill side. Sign convention (finance-math skill): quantity &gt; 0 = long. */
public enum Side {
    BUY,
    SELL;

    /** Signed quantity: BUY is positive, SELL negative. Input must be positive. */
    public BigDecimal signed(BigDecimal positiveQuantity) {
        if (positiveQuantity.signum() <= 0) {
            throw new IllegalArgumentException("quantity must be positive, got " + positiveQuantity);
        }
        return this == BUY ? positiveQuantity : positiveQuantity.negate();
    }
}
