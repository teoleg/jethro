package io.muniworld.domain;

/**
 * A bond as shown in the table — its stored terms plus the computed economic indicators (percent for
 * yields, years for duration, years² for convexity). Rounded for display; the exact terms live on
 * {@link Bond}.
 */
public record BondRow(
        String cusip,
        String issuer,
        double coupon,
        String maturity,
        double price,
        double currentYield,
        double ytm,
        double ytw,
        double modDuration,
        double convexity,
        double accrued,
        String taxStatus,
        String call,
        String rating,
        String priceAsOf,     // date the current price is as-of (null = terms-only, blank economics)
        String priceSource) { // provenance of the price, e.g. msrb-rtrs (null when unpriced)
}
