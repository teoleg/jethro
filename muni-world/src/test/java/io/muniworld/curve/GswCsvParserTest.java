package io.muniworld.curve;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parser tests for the Fed's GSW curve file (ADR-0017). The fixture deliberately mimics the real file's
 * awkward shapes: a multi-line preamble containing commas, the parameter columns at the END after the
 * SVENYnn block, blank cells in the early history, and an "NA" sentinel.
 */
class GswCsvParserTest {

    /**
     * A preamble of SEVEN rows, not the nine every third-party snippet hard-codes. The parser must find the
     * header by NAME; a fixed skip-count would read the wrong row here and silently mis-assign every column.
     */
    private static final String CSV = """
            "Federal Reserve Board, Nominal Yield Curve"
            "Gurkaynak, Sack, and Wright (2006), FEDS 2006-28"
            "Parameters are in percent; maturities in years."
            "Contact: publications, Board of Governors"
            "Updated weekly."
            ""
            "Series Description"
            Date,SVENY01,SVENY02,SVENY10,BETA0,BETA1,BETA2,BETA3,TAU1,TAU2
            2026-08-10,3.36787944,3.55,4.10,4.0,-1.0,0.0,0.0,1.0,1.0
            2026-08-11,3.40000000,3.60,4.15,4.1,-1.0,0.0,0.0,1.0,1.0
            1970-01-02,NA,4.52848224,,5.0,-2.0,3.0,,2.0,
            """;

    @Test
    void findsTheHeaderByNameNotByAFixedSkipCount() {
        List<GswCsvParser.Row> rows = GswCsvParser.parse(CSV.getBytes(StandardCharsets.UTF_8));
        assertEquals(3, rows.size());
        assertEquals(LocalDate.of(2026, 8, 10), rows.get(0).date());
        assertEquals(4.0, rows.get(0).beta0());
        assertEquals(-1.0, rows.get(0).beta1());
        assertEquals(1.0, rows.get(0).tau1());
    }

    /** The parsed parameters must rebuild the same rate the Fed published in SVENY01 for that day. */
    @Test
    void reconstructedCurveAgreesWithThePublishedSvenYield() {
        GswCsvParser.Row row = GswCsvParser.parse(CSV.getBytes(StandardCharsets.UTF_8)).get(0);
        assertEquals(new BigDecimal("0.03367879"), row.curve().zeroRate(1));
        // ...which is the file's own SVENY01 of 3.36787944 percent, to the stored scale.
        assertEquals(new BigDecimal("0.03367879"),
                BigDecimal.valueOf(row.svenYieldsPercent().get(1)).movePointLeft(2)
                        .setScale(8, java.math.RoundingMode.HALF_UP));
    }

    /** Early history has no BETA3/TAU2 — that is Nelson-Siegel, read as a zero fourth term, not a guess. */
    @Test
    void blankFourthTermIsReadAsNelsonSiegel() {
        GswCsvParser.Row old = GswCsvParser.parse(CSV.getBytes(StandardCharsets.UTF_8)).get(2);
        assertEquals(LocalDate.of(1970, 1, 2), old.date());
        assertEquals(0.0, old.beta3(), "a blank BETA3 must become an exact zero fourth term");
        assertTrue(old.tau2() > 0, "tau2 must stay positive so the model stays constructible");
        assertEquals(new BigDecimal("0.04528482"), old.curve().zeroRate(2));
    }

    /** "NA" is a missing observation, not the number zero. */
    @Test
    void naSentinelIsAMissingObservationNotZero() {
        GswCsvParser.Row old = GswCsvParser.parse(CSV.getBytes(StandardCharsets.UTF_8)).get(2);
        assertTrue(!old.svenYieldsPercent().containsKey(1), "SVENY01=NA must be absent, never 0.0");
        assertTrue(!old.svenYieldsPercent().containsKey(10), "a blank SVENY10 must be absent, never 0.0");
        assertEquals(4.52848224, old.svenYieldsPercent().get(2), "the one populated tenor must survive");
    }

    /** Rows without the parameters that define a curve are omitted, never patched with a default. */
    @Test
    void unusableRowsAreOmittedRatherThanDefaulted() {
        String csv = """
                Date,BETA0,BETA1,BETA2,BETA3,TAU1,TAU2
                2026-08-10,4.0,-1.0,0.0,0.0,1.0,1.0
                2026-08-11,,,,,,
                not-a-date,4.0,-1.0,0.0,0.0,1.0,1.0
                2026-08-12,4.0,-1.0,0.0,0.0,0.0,1.0
                """;
        List<GswCsvParser.Row> rows = GswCsvParser.parse(csv.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, rows.size(), "blank params, a bad date and a non-positive tau1 must all be dropped");
        assertEquals(LocalDate.of(2026, 8, 10), rows.get(0).date());
    }

    /** A file that is not the curve file fails loudly instead of yielding an empty, plausible-looking list. */
    @Test
    void aFileWithNoParameterHeaderIsRejectedLoudly() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> GswCsvParser.parse("some,other,csv\n1,2,3\n".getBytes(StandardCharsets.UTF_8)));
        assertTrue(e.getMessage().contains("BETA0"), e.getMessage());
    }

    /** Older vintages dated in US format must still parse. */
    @Test
    void acceptsTheUsDateFormatOfOlderVintages() {
        String csv = """
                Date,BETA0,BETA1,BETA2,BETA3,TAU1,TAU2
                8/10/2026,4.0,-1.0,0.0,0.0,1.0,1.0
                """;
        assertEquals(LocalDate.of(2026, 8, 10),
                GswCsvParser.parse(csv.getBytes(StandardCharsets.UTF_8)).get(0).date());
    }
}
