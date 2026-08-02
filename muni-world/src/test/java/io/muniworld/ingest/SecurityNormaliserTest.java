package io.muniworld.ingest;

import io.muniworld.domain.Bond;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The normalise stage (ADR-0004) must map source-shaped rows into canonical {@link Bond}s exactly — money
 * strips {@code $ , %} into exact BigDecimal, dates accept ISO / US / Socrata-datetime, tax status folds to
 * the canonical set — and must <b>quarantine</b> (skip + count) any row missing a required field rather than
 * guess (ADR-0011). Every normalised bond is real, disclosure-sourced data — the system has no illustrative
 * or sample bonds at all.
 */
class SecurityNormaliserTest {

    private final SecurityNormaliser norm = new SecurityNormaliser();

    /** A field map naming the columns of the Socrata-style rows below. */
    private static final FieldMap MAP = new FieldMap(
            "cusip", "issuer", "coupon_rate", "maturity_date", "dated_date", "dollar_price",
            "tax_status", "call_date", "call_price", "rating", "fips");

    @Test
    void mapsAndParsesAFullRow() {
        Map<String, Object> row = Map.of(
                "cusip", "649122AC9",
                "issuer", "NYC Water",
                "coupon_rate", "5.000%",
                "maturity_date", "2035-06-01T00:00:00.000",
                "dated_date", "6/1/2024",
                "dollar_price", "$108.25",
                "tax_status", "Tax-Exempt",
                "call_date", "2032-06-01",
                "call_price", "100",
                "rating", "Aa1");

        SecurityNormaliser.Result res = norm.normalise(List.of(row), MAP);

        assertEquals(0, res.skipped());
        assertEquals(1, res.bonds().size());
        Bond b = res.bonds().get(0);
        assertEquals("649122AC9", b.cusip());
        assertEquals("NYC Water", b.issuer());
        assertEquals(0, new BigDecimal("5.000").compareTo(b.coupon()), "% stripped, exact decimal");
        assertEquals(LocalDate.of(2035, 6, 1), b.maturity(), "Socrata datetime truncated to the date");
        assertEquals(LocalDate.of(2024, 6, 1), b.dated(), "US M/d/uuuu parsed");
        assertEquals(0, new BigDecimal("108.25").compareTo(b.price()), "$ and thousands separators stripped");
        assertEquals("tax-exempt", b.taxStatus());
        assertEquals(LocalDate.of(2032, 6, 1), b.callDate());
    }

    @Test
    void quarantinesRowsMissingRequiredFields() {
        // required = cusip, coupon, maturity, price; each of these rows drops one.
        List<Map<String, Object>> rows = List.of(
                Map.of("issuer", "no cusip", "coupon_rate", "5", "maturity_date", "2035-06-01", "dollar_price", "100"),
                Map.of("cusip", "111111AA1", "maturity_date", "2035-06-01", "dollar_price", "100"),   // no coupon
                Map.of("cusip", "222222AA2", "coupon_rate", "5", "dollar_price", "100"),               // no maturity
                Map.of("cusip", "333333AA3", "coupon_rate", "5", "maturity_date", "2035-06-01"),       // no price
                Map.of("cusip", "444444AA4", "coupon_rate", "4", "maturity_date", "2030-06-01", "dollar_price", "99"));

        SecurityNormaliser.Result res = norm.normalise(rows, MAP);

        assertEquals(1, res.bonds().size(), "only the complete row survives");
        assertEquals("444444AA4", res.bonds().get(0).cusip());
        assertEquals(4, res.skipped(), "the four incomplete rows are quarantined, not force-fit");
    }

    @Test
    void unparseableDateOrMoneyQuarantinesRatherThanGuess() {
        Map<String, Object> bad = Map.of(
                "cusip", "555555AA5", "coupon_rate", "5", "maturity_date", "not-a-date", "dollar_price", "100");
        SecurityNormaliser.Result res = norm.normalise(List.of(bad), MAP);
        assertEquals(0, res.bonds().size());
        assertEquals(1, res.skipped());
    }

    @Test
    void foldsTaxStatusVariants() {
        assertEquals("AMT", tax("Subject to AMT"));
        assertEquals("BAB", tax("Build America Bonds"));
        assertEquals("taxable", tax("Federally Taxable"));
        assertEquals("tax-exempt", tax("Fed & NY Tax-Exempt"));
        assertNull(tax(null));
    }

    /** Drive one row through with a given tax string, return the normalised taxStatus. */
    private String tax(String raw) {
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("cusip", "999999AA9");
        row.put("coupon_rate", "5");
        row.put("maturity_date", "2035-06-01");
        row.put("dollar_price", "100");
        row.put("tax_status", raw);
        return norm.normalise(List.of(row), MAP).bonds().get(0).taxStatus();
    }
}
