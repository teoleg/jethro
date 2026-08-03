package io.muniworld.extract;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
