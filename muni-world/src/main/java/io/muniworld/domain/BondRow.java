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
        String priceSource,   // provenance of the price, e.g. msrb-rtrs (null when unpriced)
        // ADR-0016 filing detail — fund-attested fact from N-PORT, display only (never an analytics input):
        String couponKind,    // Fixed / Floating / Zero, verbatim from the filing
        Boolean inDefault,    // the issue is in default (a fund attested it)
        Boolean intArrears,   // interest payments are in arrears
        Integer heldFunds,    // how many registered funds held it last ingested cycle
        Double heldPar,       // total par those funds held (USD issues)
        Double valPer100,     // par-weighted FILING valuation per 100 — as-of valAsOf, never a live mark
        String valAsOf,       // the filings' reporting-period date
        // "No call date" means two completely different things and they must never render the same:
        //   CALLABLE      — a document states a call date
        //   NON_CALLABLE  — an Official Statement WAS read and states no call: a FACT about the bond
        //   UNKNOWN       — no Official Statement exists for this bond yet, so its optionality is simply
        //                   not known. Treating that as "non-callable" would invent the most important
        //                   term in the whole model (ADR-0011 — never force-fit).
        String callState) {
}
