package io.jethro.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DecimalsTest {

    @Test
    void roundTripIsExact() {
        var price = new BigDecimal("123.456789");
        long scaled = Decimals.toScaledLong(price, Decimals.PRICE_SCALE);
        assertEquals(123_456_789L, scaled);
        assertEquals(price, Decimals.fromScaledLong(scaled, Decimals.PRICE_SCALE));
    }

    @Test
    void negativeValuesRoundTrip() {
        var pnl = new BigDecimal("-0.00000001");
        long scaled = Decimals.toScaledLong(pnl, Decimals.PNL_SCALE);
        assertEquals(-1L, scaled);
        assertEquals(pnl, Decimals.fromScaledLong(scaled, Decimals.PNL_SCALE));
    }

    @Test
    void precisionLossThrowsInsteadOfTruncating() {
        var tooPrecise = new BigDecimal("1.0000001"); // 7 dp into a 6 dp field
        assertThrows(ArithmeticException.class,
                () -> Decimals.toScaledLong(tooPrecise, Decimals.PRICE_SCALE));
    }

    @Test
    void overflowThrows() {
        var huge = new BigDecimal("99999999999999999999");
        assertThrows(ArithmeticException.class,
                () -> Decimals.toScaledLong(huge, Decimals.PRICE_SCALE));
    }
}
