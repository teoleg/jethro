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
              <formData>
              <genInfo><repPdDate>2026-06-30</repPdDate></genInfo>
              <invstOrSecs>
                <invstOrSec>
                  <name>SYNTHETIC NY DORMITORY AUTH REV</name>
                  <cusip>TEST00019</cusip>
                  <balance>1000000.00</balance>
                  <units>PA</units>
                  <curCd>USD</curCd>
                  <valUSD>1012500.00</valUSD>
                  <assetCat>DBT</assetCat>
                  <issuerCat>MUN</issuerCat>
                  <debtSec><maturityDt>2045-03-15</maturityDt>
                    <couponKind>Fixed</couponKind><annualizedRt>5.12500000</annualizedRt>
                    <isDefault>N</isDefault><areIntrstPmntsInArrs>N</areIntrstPmntsInArrs></debtSec>
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

        assertEquals(1, p.holdings().size(), "one complete muni row");
        var h = p.holdings().get(0);
        Bond b = h.bond();
        assertEquals("TEST00019", b.cusip());
        assertEquals("SYNTHETIC NY DORMITORY AUTH REV", b.issuer());
        assertEquals(new BigDecimal("5.125000"), b.coupon(), "exact decimal at the schema's 6dp");
        assertEquals(LocalDate.of(2045, 3, 15), b.maturity());
        assertNull(b.price(), "N-PORT valuation is NOT a current mark — no price is derived");

        // The fund-attested DETAIL beside the terms (ADR-0016 amendment): coupon kind, the two credit
        // facts, par held and the fund's USD valuation, plus the filings' period date.
        assertEquals("Fixed", h.couponKind());
        assertFalse(h.inDefault());
        assertFalse(h.intArrears());
        assertEquals(new BigDecimal("1000000.00"), h.parHeld());
        assertEquals(new BigDecimal("1012500.00"), h.valUsd());
        assertEquals(java.time.LocalDate.of(2026, 6, 30), p.periodEnd());

        assertEquals(1, p.skippedNonMuni(), "the equity row is skipped, not force-fit");
        assertEquals(1, p.quarantined(), "the cusip-less muni row is quarantined, not invented");
    }

    @Test
    void aFilersFloatNoiseInTheRateDoesNotAbortTheFund() throws Exception {
        // Real filings carry computed rates like 3.7500000000000004 — noise from the FILER's arithmetic.
        // The scaled-long index key takes 6dp exactly and threw "Rounding necessary" on anything finer,
        // which aborted the whole fund: Franklin's entire portfolio was lost to one bond. Round at the
        // schema's 6dp (lossless for a real coupon, which is quoted to 3-4dp) so the row simply lands.
        String xml = XML.replace("<annualizedRt>5.12500000</annualizedRt>",
                                 "<annualizedRt>3.7500000000000004</annualizedRt>");

        var p = EdgarNportConnector.parseHoldings(xml.getBytes(StandardCharsets.UTF_8));

        assertEquals(1, p.holdings().size(), "the bond lands rather than blowing up the fund");
        assertEquals(new BigDecimal("3.750000"), p.holdings().get(0).bond().coupon());
        assertEquals(6, p.holdings().get(0).bond().coupon().scale(), "exactly the scale the index key requires");
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
    void theRawXmlIsFetchedNotEdgarsXslViewerPath() {
        // EDGAR reports primaryDocument for an XML form as its XSL VIEWER path, which serves a rendered
        // page rather than the XML — every fund failed on exactly that URL. Only the basename is used.
        assertEquals("https://www.sec.gov/Archives/edgar/data/788599/000078859926000082/primary_doc.xml",
                EdgarNportConnector.archiveUrl("788599", "0000788599-26-000082",
                        "xslFormNPORT-P_X01/primary_doc.xml"));

        // A plain document name is unaffected, and a missing one falls back to the conventional name.
        assertEquals("https://www.sec.gov/Archives/edgar/data/718581/000003540226004120/primary_doc.xml",
                EdgarNportConnector.archiveUrl("718581", "0000035402-26-004120", "primary_doc.xml"));
        assertTrue(EdgarNportConnector.archiveUrl("818850", "0000818850-26-000014", "")
                .endsWith("/primary_doc.xml"));
    }

    @Test
    void theFilingValuationIsParWeightedExactDecimal() {
        // Worked example (finance rule): fund A holds 1,000,000 par valued $1,012,500; fund B holds
        // 500,000 par valued $505,000. Combined: (1,012,500 + 505,000) / 1,500,000 x 100 = 101.166667
        // at the schema's 6dp. Never floating point, never a division by zero.
        assertEquals(new BigDecimal("101.166667"), EdgarFundHoldingsScheduler.valPer100(
                new BigDecimal("1517500.00"), new BigDecimal("1500000.00")));
        assertNull(EdgarFundHoldingsScheduler.valPer100(new BigDecimal("100"), BigDecimal.ZERO),
                "no reported par means no valuation, not an exception");
        assertNull(EdgarFundHoldingsScheduler.valPer100(null, null));
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
