package io.muniworld.bond;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The coverage statement is assembled by concatenation, and concatenation is exactly how it broke: two
 * Java text blocks glued together produced {@code ...count(source_id) = 0ORDER BY count(*) DESC}, which
 * threw on every call. The plan rendered EMPTY while the bonds sat untouched in the table — a silent
 * failure that looked like data loss.
 *
 * <p>A database is not needed to catch that: the defect is visible in the string. These assertions are
 * the guard rail that concatenated SQL keeps its separators.
 */
class CoverageSqlTest {

    @Test
    void everyClauseIsSeparated() {
        for (boolean includeDone : new boolean[] {false, true}) {
            String sql = SecurityRepository.coverageSql(includeDone);

            // The exact defect that shipped: a clause welded onto the token before it.
            assertFalse(sql.contains("0ORDER"), "HAVING ran into ORDER BY: " + sql);
            assertFalse(sql.contains(")ORDER"), "GROUP BY ran into ORDER BY: " + sql);
            assertFalse(sql.matches("(?s).*\\S(ORDER BY|HAVING|LIMIT)\\b.*"),
                    "a keyword must never touch the token before it: " + sql);

            // And the statement still says what it is meant to say.
            assertTrue(sql.contains("GROUP BY substring(cusip, 1, 6)"));
            assertTrue(sql.contains("ORDER BY count(*) DESC"));
            assertTrue(sql.trim().endsWith("LIMIT ?"));
        }
    }

    @Test
    void theToDoFilterIsPresentOnlyWhenHidingLoadedIssuers() {
        // Hiding done issuers is the whole point of the plan; showing everything must NOT filter.
        assertTrue(SecurityRepository.coverageSql(false).contains("HAVING count(source_id) = 0"),
                "the to-do list keeps only issuers whose OS has not been read");
        assertFalse(SecurityRepository.coverageSql(true).contains("HAVING"),
                "show-all must return every issuer");
    }

    /**
     * The upsert must MERGE, never trade knowledge for ignorance. Two sources write this table and they
     * know different things: the N-PORT fund feed carries cusip/issuer/coupon/maturity and nothing else,
     * an Official Statement carries the call schedule, tax status and provenance.
     *
     * <p>With plain {@code = EXCLUDED.x} the daily fund pass re-wrote every row with null call_date,
     * tax_status and source_id — silently destroying the call schedules the OS pipeline exists to obtain,
     * and making the coverage plan and readiness counts move on their own. Every updatable field must be
     * COALESCEd, so a source that does not carry a field cannot erase it.
     */
    @Test
    void theUpsertNeverErasesAKnownValueWithANull() {
        String sql = SecurityRepository.upsertSql();
        String doUpdate = sql.substring(sql.indexOf("DO UPDATE SET"));

        for (String field : new String[] {"issuer", "coupon", "maturity_date", "dated_date", "price",
                                          "tax_status", "call_date", "call_price", "rating", "geo_fips",
                                          "source_id"}) {
            assertTrue(doUpdate.contains("COALESCE(EXCLUDED." + field + ","),
                    field + " must be COALESCEd or a source that lacks it will erase it:\n" + doUpdate);
            assertFalse(doUpdate.matches("(?s).*\\b" + field + "\\s*=\\s*EXCLUDED\\." + field + "\\b.*"),
                    field + " is assigned bare from EXCLUDED — that is the erasing form:\n" + doUpdate);
        }
        // The one field that SHOULD be written unconditionally.
        assertTrue(doUpdate.contains("updated_at    = now()"), doUpdate);
    }
}
