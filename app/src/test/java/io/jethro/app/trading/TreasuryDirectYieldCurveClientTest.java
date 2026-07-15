package io.jethro.app.trading;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parsing of the official treasury.gov daily par-yield XML (ADR-0024 follow-up): takes the
 * MOST RECENT complete day, converts percent → fraction, skips incomplete days, never throws.
 * Fixture mirrors the feed's OData shape (d:BC_* properties per day entry).
 */
class TreasuryDirectYieldCurveClientTest {

    private static String day(String date, String y1, String y2, String y5, String y10, String y30) {
        return "<entry><content type=\"application/xml\"><m:properties>"
                + "<d:NEW_DATE m:type=\"Edm.DateTime\">" + date + "T00:00:00</d:NEW_DATE>"
                + "<d:BC_1MONTH m:type=\"Edm.Double\">5.30</d:BC_1MONTH>"
                + "<d:BC_1YEAR m:type=\"Edm.Double\">" + y1 + "</d:BC_1YEAR>"
                + "<d:BC_2YEAR m:type=\"Edm.Double\">" + y2 + "</d:BC_2YEAR>"
                + "<d:BC_3YEAR m:type=\"Edm.Double\">4.51</d:BC_3YEAR>"
                + "<d:BC_5YEAR m:type=\"Edm.Double\">" + y5 + "</d:BC_5YEAR>"
                + "<d:BC_10YEAR m:type=\"Edm.Double\">" + y10 + "</d:BC_10YEAR>"
                + "<d:BC_30YEAR m:type=\"Edm.Double\">" + y30 + "</d:BC_30YEAR>"
                + "</m:properties></content></entry>";
    }

    @Test
    void takesTheMostRecentCompleteDayInPercentToFraction() {
        String xml = "<feed>" +
                day("2026-07-10", "4.90", "4.65", "4.45", "4.38", "4.55") +
                day("2026-07-11", "4.85", "4.60", "4.40", "4.35", "4.50") + "</feed>";

        double[] zeros = TreasuryDirectYieldCurveClient.parseLatestNodeZeros(xml);

        // The LAST (newest) day wins; 4.85% → 0.0485 etc.
        assertEquals(0.0485, zeros[0], 1e-12);
        assertEquals(0.0460, zeros[1], 1e-12);
        assertEquals(0.0440, zeros[2], 1e-12);
        assertEquals(0.0435, zeros[3], 1e-12);
        assertEquals(0.0450, zeros[4], 1e-12);
    }

    @Test
    void anIncompleteNewestDayFallsBackToTheLastCompleteOne() {
        // Newest entry is missing BC_30YEAR (e.g. a null-typed tag) — the parser must not
        // guess a node (finance-math rule) and returns the previous complete day instead.
        String incomplete = "<entry><content><m:properties>"
                + "<d:BC_1YEAR m:type=\"Edm.Double\">4.80</d:BC_1YEAR>"
                + "<d:BC_2YEAR m:type=\"Edm.Double\">4.55</d:BC_2YEAR>"
                + "<d:BC_5YEAR m:type=\"Edm.Double\">4.35</d:BC_5YEAR>"
                + "<d:BC_10YEAR m:type=\"Edm.Double\">4.30</d:BC_10YEAR>"
                + "<d:BC_30YEAR m:type=\"Edm.Double\" m:null=\"true\"></d:BC_30YEAR>"
                + "</m:properties></content></entry>";
        String xml = "<feed>" + day("2026-07-10", "4.90", "4.65", "4.45", "4.38", "4.55")
                + incomplete + "</feed>";

        double[] zeros = TreasuryDirectYieldCurveClient.parseLatestNodeZeros(xml);
        assertEquals(0.0490, zeros[0], 1e-12, "fell back to the 07-10 complete day");
        assertEquals(0.0455, zeros[4], 1e-12);
    }

    @Test
    void emptyOrGarbageYieldsNullNeverThrows() {
        assertNull(TreasuryDirectYieldCurveClient.parseLatestNodeZeros(null));
        assertNull(TreasuryDirectYieldCurveClient.parseLatestNodeZeros(""));
        assertNull(TreasuryDirectYieldCurveClient.parseLatestNodeZeros("<feed>no entries yet</feed>"));
        assertNull(TreasuryDirectYieldCurveClient.parseLatestNodeZeros("not xml at all"));
    }

    @Test
    void sourceNamesTheProvider() {
        assertTrue(new TreasuryDirectYieldCurveClient(java.time.Duration.ofSeconds(5))
                .source().contains("treasury.gov"));
    }
}
