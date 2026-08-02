package io.muniworld.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A municipal bond's stored terms (ADR-0006 security subset) — money fields are exact {@code BigDecimal}.
 * {@code price} is the clean price per 100; {@code callDate}/{@code callPrice} drive yield-to-worst. The
 * economic indicators (yields, duration, convexity, accrued) are computed on read by {@link BondMath}, not
 * stored. {@code sample} flags illustrative seed data vs real disclosure-sourced bonds.
 */
public record Bond(
        String cusip,
        String issuer,
        BigDecimal coupon,      // percent, e.g. 5.00
        LocalDate maturity,
        LocalDate dated,
        BigDecimal price,       // clean, per 100
        String taxStatus,
        LocalDate callDate,
        BigDecimal callPrice,   // per 100
        String rating,
        String geoFips,
        boolean sample) {
}
