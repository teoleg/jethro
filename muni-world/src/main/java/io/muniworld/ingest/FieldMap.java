package io.muniworld.ingest;

/**
 * Declares which keys in a source row (a Socrata JSON object, a parsed EMMA/OS field set, a CSV row) hold
 * each {@link io.muniworld.domain.Bond} field. The ADR-0004 normalise stage is source-agnostic: point the
 * map at a source's column names and the same normaliser turns its rows into canonical bonds. A null entry
 * means "this source doesn't carry that field".
 */
public record FieldMap(
        String cusip,
        String issuer,
        String coupon,
        String maturity,
        String dated,
        String price,
        String taxStatus,
        String callDate,
        String callPrice,
        String rating,
        String geoFips) {
}
