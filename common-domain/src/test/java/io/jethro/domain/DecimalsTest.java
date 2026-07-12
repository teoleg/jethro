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

    @Test
    void atScalePadsToTheFieldScaleForAvroEncoding() {
        // A user-entered whole quantity ("100", scale 0) must be restated at the
        // Avro field's declared scale or the decimal encoder rejects it.
        var qty = new BigDecimal("100");
        var restated = Decimals.atScale(qty, Decimals.QTY_SCALE);
        assertEquals(6, restated.scale());
        assertEquals(0, new BigDecimal("100.000000").compareTo(restated));
    }

    @Test
    void atScaleRejectsOverPreciseValuesInsteadOfRounding() {
        var tooPrecise = new BigDecimal("1.0000001"); // 7 dp into a 6 dp field
        assertThrows(ArithmeticException.class,
                () -> Decimals.atScale(tooPrecise, Decimals.PRICE_SCALE));
    }
}
