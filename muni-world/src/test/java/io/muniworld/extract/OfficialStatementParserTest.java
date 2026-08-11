package io.muniworld.extract;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ADR-0015 OS parser on a representative maturity schedule: it lifts per-CUSIP <b>terms</b> (coupon,
 * maturity, tax) and attaches the optional call only to the bonds the redemption clause actually gates —
 * and it emits <b>no price</b> (the schedule's yield is reoffering, not a current mark). Prose like the
 * redemption sentence is not mistaken for a bond row.
 */
class OfficialStatementParserTest {

    // A compact stand-in for the extracted text of a real OS cover/inside-cover maturity schedule.
    private static final String OS_TEXT = """
            $50,000,000
            CITY OF NEW YORK
            General Obligation Bonds, Fiscal 2025 Series A

            MATURITY SCHEDULE
            $30,000,000 Serial Bonds
            Base CUSIP: 649122

            Maturity (November 1)   Principal Amount   Interest Rate   Yield    CUSIP
            2026                    $1,000,000         5.000%          3.100%   649122AB1
            2027                    $1,050,000         5.000%          3.250%   649122AC9
            2028                    $1,100,000         4.000%          3.400%   649122AD7

            $20,000,000 Term Bonds
            2040                    $20,000,000        4.000%          4.050%   649122AE5

            The Bonds maturing on or after November 1, 2034 are subject to optional redemption prior to
            maturity, at the option of the City, on or after November 1, 2033, at a redemption price of 100%
            of the principal amount.

            In the opinion of Bond Counsel, interest on the Bonds is excluded from gross income for federal
            income tax purposes.
            """;

    /**
     * The real-world layout that yielded ZERO bonds from a 280-page OS (Omaha Airport Authority, Series
     * 2026A/B — the owner's first live document). Three things defeated the parser at once, and each is
     * asserted here verbatim from that document's own text:
     *
     * <ul>
     *   <li>the schedule prints CUSIP <b>suffixes</b> under a base stated as "(681725)*" in the column
     *       header — not the literal wording "Base CUSIP" the parser looked for;</li>
     *   <li>the table is <b>two columns side by side</b>, so one text line carries TWO bonds;</li>
     *   <li>two series with different tax treatment (AMT / Non-AMT) are priced in one document.</li>
     * </ul>
     */
    @Test
    void readsATwoColumnSuffixScheduleWithPerSeriesTax() {
        String os = """
                MATURITY SCHEDULE
                $162,270,000
                Airport Facilities Revenue Bonds (AMT), Series 2026A
                Maturity Principal Interest   CUSIP Maturity Principal Interest   CUSIP
                (December 15) Amount Rate Yield Price (681725)* (December 15) Amount Rate Yield Price (681725)*
                2027 $2,395,000 5.000% 2.930% 102.777% NM5 2037 $3,900,000 5.000% 3.860%† 109.670% NX1
                2028 2,520,000 5.000 3.020 104.511 NN3 2038 4,100,000 5.000 3.950† 108.866 NY9
                $35,925,000 5.500% Term Bond due December 15, 2051, Yield 4.650%† Price 106.930%, CUSIP Number* 681725 PH4
                †Yield to first optional call date of December 15, 2036.
                $45,525,000
                Airport Facilities Revenue Bonds (Non-AMT), Series 2026B
                (December 15) Amount Rate Yield Price (681725)* (December 15) Amount Rate Yield Price (681725)*
                2029 $   780,000 5.000% 2.720% 107.313% PK7 2038 $1,205,000 5.000% 3.590%† 112.125% PU5
                """;

        var res = OfficialStatementParser.parse(os, "Airport Authority of the City of Omaha", "31", "");

        // 2 lines x 2 columns for series A + 1 term bond + 1 line x 2 columns for series B = 7 bonds.
        assertEquals(7, res.rows().size(), "both columns of every schedule line, plus the term bond");

        var byCusip = new java.util.HashMap<String, java.util.Map<String, Object>>();
        res.rows().forEach(r -> byCusip.put(String.valueOf(r.get("cusip")), r));

        // Base CUSIP-6 from the "(681725)*" header, assembled with each row's suffix.
        var first = byCusip.get("681725NM5");
        assertNotNull(first, "the base from the parenthesised header + the row's suffix");
        assertEquals("5.000", first.get("coupon"));
        assertEquals("2027-12-15", first.get("maturity"), "bare year + the schedule's (December 15) header");
        assertEquals("2.930", first.get("reofferingYield"), "reoffering yield captured, never emitted as price");
        assertNull(first.get("price"), "terms only — an OS states no CURRENT price");

        // The SECOND bond on the same physical line — the half the old parser silently dropped.
        var second = byCusip.get("681725NX1");
        assertNotNull(second, "the right-hand column of the same line is a bond too");
        assertEquals("2037-12-15", second.get("maturity"));

        // Term bond, printed as prose with its own full base+suffix.
        var term = byCusip.get("681725PH4");
        assertNotNull(term);
        assertEquals("5.500", term.get("coupon"));
        assertEquals("2051-12-15", term.get("maturity"));

        // Per-series tax read from the headings — one document, two treatments, neither guessed.
        assertEquals("AMT", first.get("tax"), "Series 2026A is the AMT tranche");
        assertEquals("tax-exempt", byCusip.get("681725PK7").get("tax"), "Series 2026B is Non-AMT");

        // The call: date from the footnote, applied ONLY to maturities after it, and NO price invented —
        // the sentence states a date and no redemption price.
        assertEquals("2036-12-15", second.get("callDate"), "a 2037 maturity is callable at the 2036 call");
        assertNull(second.get("callPrice"), "no price is stated, so none is written — never assumed par");
        assertNull(first.get("callDate"), "a 2027 maturity matures before the call — not callable");
    }

    @Test
    void extractsTermsCallAndTaxButNoPrice() {
        OfficialStatementParser.Result res =
                OfficialStatementParser.parse(OS_TEXT, "City of New York", "3600000000", null);

        List<Map<String, Object>> rows = res.rows();
        assertEquals(4, rows.size(), "four schedule rows parsed");
        assertEquals(0, res.quarantined(), "the redemption prose is not mistaken for a failed row");
        assertEquals(1.0, res.confidence(), 1e-9);

        Map<String, Object> r2026 = row(rows, "649122AB1");
        assertEquals("5.000", r2026.get("coupon"));
        assertEquals("2026-11-01", r2026.get("maturity"), "bare year + '(November 1)' header → full date");
        assertEquals("tax-exempt", r2026.get("tax"));
        assertNull(r2026.get("price"), "NO price emitted — the schedule yield is reoffering, not a current mark");
        assertNull(r2026.get("callDate"), "2026 matures before the 2034 call gate → not callable");
        assertEquals("3.100", r2026.get("reofferingYield"), "reoffering yield captured, kept out of price");

        Map<String, Object> r2040 = row(rows, "649122AE5");
        assertEquals("2040-11-01", r2040.get("maturity"));
        assertEquals("2033-11-01", r2040.get("callDate"), "2040 ≥ 2034 gate → callable on the redemption date");
        assertEquals("100", r2040.get("callPrice"));
    }

    // A real EMMA OS commonly prints the base CUSIP-6 once, in wording this parser does not match, and then
    // only 2–3 character SUFFIXES per maturity row.
    private static final String SUFFIX_ONLY_OS = """
            MATURITY SCHEDULE
            (Due November 1)
            Year   Principal   Coupon   Yield   CUSIP No.†
            2027   $1,000,000   5.000%   3.10%   AB1
            2028   $1,050,000   5.000%   3.25%   AC9

            † CUSIP numbers are provided by CUSIP Global Services.
            """;

    @Test
    void neverEmitsAPartialCusipWhenNoBaseIsKnown() {
        // The loaders pass "" as the fallback base. "" is non-null, so this used to reach the suffix branch
        // and emit "" + "AB1" = "AB1" — a FABRICATED 3-character identity, indexed as a real security.
        OfficialStatementParser.Result res = OfficialStatementParser.parse(SUFFIX_ONLY_OS, "City", null, "");

        assertTrue(res.rows().isEmpty(), "no base CUSIP → no rows; never a partial key");
        for (Map<String, Object> r : res.rows()) {
            assertEquals(9, String.valueOf(r.get("cusip")).length(), "a CUSIP is 9 chars or it is not a CUSIP");
        }
        assertEquals(2, res.quarantined(),
                "the two unkeyable schedule rows are QUARANTINED, not silently skipped — otherwise a "
                + "suffix-style OS reports '0 rows, 0 quarantined', i.e. 'there was no schedule'");
    }

    @Test
    void assemblesSuffixesOnlyAgainstARealBase() {
        // Same document, base supplied by the caller (e.g. the CUSIP-6 of the OS being ingested).
        OfficialStatementParser.Result res =
                OfficialStatementParser.parse(SUFFIX_ONLY_OS, "City", null, "649122");

        assertEquals(2, res.rows().size());
        assertEquals("649122AB1", res.rows().get(0).get("cusip"));
        assertEquals("2027-11-01", res.rows().get(0).get("maturity"));
        assertEquals(0, res.quarantined(), "keyable rows are parsed, not quarantined");
    }

    @Test
    void emptyTextYieldsNothing() {
        OfficialStatementParser.Result res = OfficialStatementParser.parse("", "x", null, null);
        assertTrue(res.rows().isEmpty());
        assertEquals(0.0, res.confidence(), 1e-9);
    }

    private static Map<String, Object> row(List<Map<String, Object>> rows, String cusip) {
        return rows.stream().filter(r -> cusip.equals(r.get("cusip"))).findFirst().orElseThrow();
    }
}
