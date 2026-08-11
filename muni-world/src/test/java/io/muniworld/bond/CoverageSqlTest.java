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
}
