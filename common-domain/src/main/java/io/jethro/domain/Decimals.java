package io.jethro.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Conversions between boundary money representation ({@link BigDecimal}) and the
 * hot-path representation (scaled-long decimal fixed-point). Per invariant 1: exact
 * decimal semantics everywhere; binary floating point on money is a bug.
 *
 * <p>The scale of every scaled-long field is declared at the field, using the
 * constants below. Conversions must go through this class — never hand-rolled.
 */
public final class Decimals {

    /** Scale for prices and marks: 1e-6 units. */
    public static final int PRICE_SCALE = 6;

    /** Scale for quantities: 1e-6 units (fractional quantities: FX, crypto-ready). */
    public static final int QTY_SCALE = 6;

    /** Scale for average cost and PnL figures: 1e-8 units. */
    public static final int PNL_SCALE = 8;

    private Decimals() {
    }

    /**
     * Converts a boundary value to a scaled long. Exact: throws
     * {@link ArithmeticException} if the value has more precision than {@code scale}
     * allows or its unscaled value overflows a long — silent truncation of money is
     * a bug, not a rounding decision.
     */
    public static long toScaledLong(BigDecimal value, int scale) {
        return value.setScale(scale, RoundingMode.UNNECESSARY).unscaledValue().longValueExact();
    }

    /** Converts a scaled long back to its exact {@link BigDecimal} value. */
    public static BigDecimal fromScaledLong(long scaled, int scale) {
        return BigDecimal.valueOf(scaled, scale);
    }

    /**
     * Restates a boundary value at exactly {@code scale} for an Avro/JDBC decimal field
     * whose scale is fixed by contract. Padding-only ("100" → "100.000000"); throws
     * {@link ArithmeticException} if the value carries more precision than {@code scale}
     * allows — Avro's decimal encoder rejects a scale mismatch, and silently rounding
     * money to fit would be a bug (invariant 1).
     */
    public static BigDecimal atScale(BigDecimal value, int scale) {
        return value.setScale(scale, RoundingMode.UNNECESSARY);
    }
}
