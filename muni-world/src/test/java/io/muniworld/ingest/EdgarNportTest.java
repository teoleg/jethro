package io.muniworld.ingest;

import io.muniworld.domain.Bond;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ADR-0016 N-PORT ingest contract: only municipal debt with complete durable terms becomes a bond;
 * everything else is counted and skipped; and a mistyped CIK is REFUSED by the registrant-name gate rather
 * than silently pouring another fund's holdings into the security master.
 *
 * <p>The XML below is a synthetic minimal NPORT-P shape (namespaced like the real form) — a test fixture,
 * not sample data in the product.
 */
class EdgarNportTest {

    private static final String XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <edgarSubmission xmlns="http://www.sec.gov/edgar/nport">
              <formData><invstOrSecs>
                <invstOrSec>
                  <name>SYNTHETIC NY DORMITORY AUTH REV</name>
                  <cusip>TEST00019</cusip>
                  <assetCat>DBT</assetCat>
                  <issuerCat>MUN</issuerCat>
                  <debtSec><maturityDt>2045-03-15</maturityDt>
                    <couponKind>Fixed</couponKind><annualizedRt>5.12500000</annualizedRt></debtSec>
                </invstOrSec>
                <invstOrSec>
                  <name>SYNTHETIC EQUITY CO</name>
                  <cusip>TEST00027</cusip>
                  <assetCat>EC</assetCat>
                  <issuerCat>CORP</issuerCat>
                </invstOrSec>
                <invstOrSec>
                  <name>SYNTHETIC MUNI MISSING CUSIP</name>
                  <cusip></cusip>
                  <assetCat>DBT</assetCat>
                  <issuerCat>MUN</issuerCat>
                  <debtSec><maturityDt>2030-01-01</maturityDt><annualizedRt>4.0</annualizedRt></debtSec>
                </invstOrSec>
              </invstOrSecs></formData>
            </edgarSubmission>
            """;

    @Test
    void onlyCompleteMuniDebtBecomesABond() throws Exception {
        var p = EdgarNportConnector.parseHoldings(XML.getBytes(StandardCharsets.UTF_8));

        assertEquals(1, p.bonds().size(), "one complete muni row");
        Bond b = p.bonds().get(0);
        assertEquals("TEST00019", b.cusip());
        assertEquals("SYNTHETIC NY DORMITORY AUTH REV", b.issuer());
        assertEquals(new BigDecimal("5.125"), b.coupon(), "rate lands as an exact decimal, zeros stripped");
        assertEquals(LocalDate.of(2045, 3, 15), b.maturity());
        assertNull(b.price(), "N-PORT valuation is NOT a current mark — no price is derived");

        assertEquals(1, p.skippedNonMuni(), "the equity row is skipped, not force-fit");
        assertEquals(1, p.quarantined(), "the cusip-less muni row is quarantined, not invented");
    }

    @Test
    void aMistypedCikIsRefusedByTheNameGate() {
        // The gate is EDGAR's own registrant name: expecting "new york" but landing on an equity trust
        // must refuse with both names in the message — a wrong CIK pollutes the master silently otherwise.
        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                EdgarNportConnector.requireNameMatches("SOME EQUITY TRUST", "new york", "999999"));
        assertTrue(e.getMessage().contains("SOME EQUITY TRUST") && e.getMessage().contains("new york"));

        // Case-insensitive containment passes.
        EdgarNportConnector.requireNameMatches("VANGUARD NEW YORK TAX-FREE FUNDS", "new york", "788599");
    }

    @Test
    void theWholeLatestQuarterlyCycleIsIngestedNotJustOneSeries() {
        // A registrant TRUST holds several series (money market + long-term + …) and each files its own
        // NPORT-P days apart. Taking only the newest filing captures ONE series and silently drops the
        // rest — the window must collect the whole latest cycle while excluding the previous quarter.
        List<String> forms = List.of("NPORT-P", "N-CEN", "NPORT-P", "NPORT-P", "NPORT-P");
        List<String> dates = List.of("2026-05-28", "2026-05-01", "2026-05-27", "2026-02-25", "2026-02-24");

        var idx = EdgarNportConnector.latestCycleIndexes(forms, dates);

        assertEquals(List.of(0, 2), idx, "both series in the May cycle; February's cycle and the N-CEN excluded");
        assertEquals(List.of(), EdgarNportConnector.latestCycleIndexes(List.of("N-CSR"), List.of("2026-01-01")),
                "no NPORT-P at all → empty, and the caller refuses loudly");
    }

    @Test
    void fundRegistryParsesAndSkipsMalformedRows() {
        List<EdgarFundCatalog.Fund> funds = EdgarFundCatalog.parse("""
                cik|expect_name|label|enabled
                # comment
                788599|new york|Vanguard NY municipal funds|true
                12345|jersey|Some NJ fund|false
                notacik|x|broken row|true
                """);
        assertEquals(2, funds.size(), "header/comment/malformed rows are dropped");
        assertTrue(funds.get(0).enabled());
        assertEquals("788599", funds.get(0).cik());
        assertFalse(funds.get(1).enabled());
    }
}
