package io.jethro.app.discovery;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The candidate-additions register (ADR-0050 §7): cross-source names outrank single-source ones,
 *  weights accumulate, and the register is bounded by eviction of the weakest. */
class UniverseCandidatesTest {

    @Test
    void crossSourceOutranksSingleSourceEvenWithMoreMentions() {
        var c = new UniverseCandidates(100);
        // TSLA: one outlet, three mentions (weight 3 each = 9). SNOW: two outlets (weight 3 each = 6)
        // + the distinct-source bonus (2 × 5 = 10) = 16 → SNOW ranks first.
        c.observe("TSLA", "news:reuters", 3.0, "Tesla headline", 1);
        c.observe("TSLA", "news:reuters", 3.0, "Tesla headline 2", 2);
        c.observe("TSLA", "news:reuters", 3.0, "Tesla headline 3", 3);
        c.observe("SNOW", "news:reuters", 3.0, "Snowflake headline", 1);
        c.observe("SNOW", "news:bloomberg", 3.0, "Snowflake elsewhere", 2);
        List<UniverseCandidate> ranked = c.ranked(10);
        assertEquals("SNOW", ranked.get(0).instrumentId(), "two independent outlets outrank one loud one");
        assertEquals(2, ranked.get(0).sources().size());
        assertEquals("TSLA", ranked.get(1).instrumentId());
    }

    @Test
    void mentionsAndFirstSeenAccumulate() {
        var c = new UniverseCandidates(100);
        c.observe("ARM", "social", 2.0, "first", 100);
        c.observe("ARM", "news:reuters", 3.0, "second", 200);
        var top = c.ranked(1).get(0);
        assertEquals(2, top.mentions());
        assertEquals(100, top.firstSeenMillis());
        assertEquals(200, top.lastSeenMillis());
        assertEquals(2, top.sources().size());
        assertEquals(1, top.distinctDays(), "both mentions land in the same UTC day");
        assertEquals("first", top.sample(), "the first sample is retained");
    }

    @Test
    void distinctDaysCountsCalendarDaysNotMentions() {
        var c = new UniverseCandidates(100);
        long day = 86_400_000L;
        c.observe("PLTR", "news:reuters", 3.0, "d0 morning", 9 * 3600_000L);   // day 0
        c.observe("PLTR", "news:reuters", 3.0, "d0 afternoon", 15 * 3600_000L); // day 0 again
        c.observe("PLTR", "news:reuters", 3.0, "d2", 2 * day + 3600_000L);       // day 2
        var top = c.ranked(1).get(0);
        assertEquals(3, top.mentions(), "three mentions");
        assertEquals(2, top.distinctDays(), "but only two distinct calendar days — sustain, not burst");
    }

    @Test
    void theRegisterIsBoundedByEviction() {
        var c = new UniverseCandidates(16);
        for (int i = 0; i < 40; i++) {
            c.observe("SYM" + i, "news:x", 1.0 + (i % 5), "h" + i, i);
        }
        assertTrue(c.ranked(1000).size() <= 16, "never grows beyond the cap");
    }
}
