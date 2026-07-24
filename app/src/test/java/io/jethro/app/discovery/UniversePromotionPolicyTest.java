package io.jethro.app.discovery;

import io.jethro.app.discovery.UniversePromotionPolicy.Context;
import io.jethro.app.discovery.UniversePromotionPolicy.Outcome;
import io.jethro.app.discovery.UniversePromotionPolicy.Thresholds;
import io.jethro.app.discovery.UniversePromotionPolicy.Verdict;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ADR-0060 promotion gate. Conservative by construction: a candidate is admitted only when it clears
 * EVERY gate, and the reason reports the FIRST failing gate in the fixed order (blacklist → tracked →
 * score → sustained → corroborated → coverage → budget).
 */
class UniversePromotionPolicyTest {

    // minScore 25, minMentions 1 (non-blocking here), minSustainedDays 3, minSources 2, budget 2.
    private static final Thresholds T = new Thresholds(25.0, 1, 3, 2, 2);
    private final UniversePromotionPolicy policy = new UniversePromotionPolicy(T);

    /** A candidate with the given score, distinct-source count (also used as mention count), distinct days. */
    private static UniverseCandidate cand(String id, double score, int sources, int distinctDays) {
        Set<String> s = new LinkedHashSet<>();
        for (int i = 0; i < sources; i++) {
            s.add("src" + i);
        }
        return new UniverseCandidate(id, score, sources, s, 1_000L, 2_000L, distinctDays, "sample headline");
    }

    private static Context ctx(Set<String> tracked, Set<String> blacklist, Predicate<String> covered, int used) {
        return new Context(tracked, blacklist, covered, used);
    }

    private static final Predicate<String> ALL_COVERED = id -> true;
    private static final Predicate<String> NONE_COVERED = id -> false;

    @Test
    void promotesWhenEveryGateClears() {
        Verdict v = policy.evaluate(cand("PLTR", 30.0, 2, 3), ctx(Set.of(), Set.of(), ALL_COVERED, 0));
        assertTrue(v.promote());
        assertEquals(Outcome.PROMOTE, v.outcome());
        assertEquals("PLTR", v.instrumentId());
    }

    @Test
    void blacklistWinsOverEverythingElse() {
        // Perfect on every other axis, but banned → BLACKLISTED, reported first.
        Verdict v = policy.evaluate(cand("BANNED", 999.0, 5, 10), ctx(Set.of(), Set.of("BANNED"), ALL_COVERED, 0));
        assertFalse(v.promote());
        assertEquals(Outcome.BLACKLISTED, v.outcome());
    }

    @Test
    void alreadyTrackedIsNotRePromoted() {
        Verdict v = policy.evaluate(cand("AAPL", 30.0, 2, 3), ctx(Set.of("AAPL"), Set.of(), ALL_COVERED, 0));
        assertEquals(Outcome.ALREADY_TRACKED, v.outcome());
    }

    @Test
    void lowScoreIsRejected() {
        Verdict v = policy.evaluate(cand("X", 24.9, 2, 3), ctx(Set.of(), Set.of(), ALL_COVERED, 0));
        assertEquals(Outcome.LOW_SCORE, v.outcome());
    }

    @Test
    void aBurstThatIsNotSustainedIsRejected() {
        // High score, corroborated, covered — but seen on only 2 distinct days (< 3 required).
        Verdict v = policy.evaluate(cand("X", 40.0, 3, 2), ctx(Set.of(), Set.of(), ALL_COVERED, 0));
        assertEquals(Outcome.NOT_SUSTAINED, v.outcome());
    }

    @Test
    void aSingleSourceNameIsNotCorroborated() {
        Verdict v = policy.evaluate(cand("X", 40.0, 1, 5), ctx(Set.of(), Set.of(), ALL_COVERED, 0));
        assertEquals(Outcome.NOT_CORROBORATED, v.outcome());
    }

    @Test
    void anUnmarkableNameIsNeverAdmitted() {
        Verdict v = policy.evaluate(cand("X", 40.0, 3, 5), ctx(Set.of(), Set.of(), NONE_COVERED, 0));
        assertEquals(Outcome.NO_FEED_COVERAGE, v.outcome());
    }

    @Test
    void budgetIsCheckedLastSoOnlyQualifiersAreRateLimited() {
        // Fully qualifies, but the day's budget (2) is already spent.
        Verdict v = policy.evaluate(cand("X", 40.0, 3, 5), ctx(Set.of(), Set.of(), ALL_COVERED, 2));
        assertEquals(Outcome.RATE_LIMITED, v.outcome());
    }

    @Test
    void reportsTheFirstFailingGateNotAllOfThem() {
        // Low score AND single-source AND unmarkable — the FIRST gate (score) is reported.
        Verdict v = policy.evaluate(cand("X", 1.0, 1, 1), ctx(Set.of(), Set.of(), NONE_COVERED, 0));
        assertEquals(Outcome.LOW_SCORE, v.outcome());
    }

    @Test
    void tooFewMentionsIsRejected() {
        // score/sources/days all fine, but only 10 mentions when 50 are required.
        var strict = new UniversePromotionPolicy(new Thresholds(25.0, 50, 1, 1, 2));
        var c = new UniverseCandidate("X", 300.0, 10, Set.of("s1", "s2"), 1L, 2L, 5, "sample");
        Verdict v = strict.evaluate(c, ctx(Set.of(), Set.of(), ALL_COVERED, 0));
        assertEquals(Outcome.LOW_MENTIONS, v.outcome());
    }

    @Test
    void scoreAndMentionsTogetherAdmit() {
        // The owner rule: score > threshold AND mentions > threshold → promote (other gates non-blocking).
        var rule = new UniversePromotionPolicy(new Thresholds(250.0, 50, 1, 1, 2));
        var c = new UniverseCandidate("TSLA", 1000.0, 330, Set.of("s1"), 1L, 2L, 1, "hot");
        assertTrue(rule.evaluate(c, ctx(Set.of(), Set.of(), ALL_COVERED, 0)).promote());
    }

    @Test
    void thresholdsValidateTheirInputs() {
        assertThrows(IllegalArgumentException.class, () -> new Thresholds(-1, 0, 3, 2, 2));
        assertThrows(IllegalArgumentException.class, () -> new Thresholds(25, -1, 3, 2, 2));
        assertThrows(IllegalArgumentException.class, () -> new Thresholds(25, 0, 0, 2, 2));
        assertThrows(IllegalArgumentException.class, () -> new Thresholds(25, 0, 3, 0, 2));
        assertThrows(IllegalArgumentException.class, () -> new Thresholds(25, 0, 3, 2, -1));
    }
}
