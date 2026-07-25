package io.jethro.app.discovery;

import io.jethro.app.discovery.UniversePromotionPolicy.Outcome;
import io.jethro.app.discovery.UniversePromotionPolicy.Thresholds;
import io.jethro.app.discovery.UniversePromotionPolicy.Verdict;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The evaluator threads the daily promotion budget down the ranking: equally-qualified names past the
 *  budget surface as RATE_LIMITED, and non-qualifying names never consume the budget. */
class UniversePromotionEvaluatorTest {

    private static final Thresholds T = new Thresholds(25.0, 1, 3, 2, 2); // minMentions 1; budget = 2/day
    private final UniversePromotionEvaluator evaluator =
            new UniversePromotionEvaluator(new UniversePromotionPolicy(T));

    private static UniverseCandidate cand(String id, double score, int sources, int distinctDays) {
        Set<String> s = new LinkedHashSet<>();
        for (int i = 0; i < sources; i++) {
            s.add("src" + i);
        }
        return new UniverseCandidate(id, score, sources, s, 1_000L, 2_000L, distinctDays, "sample");
    }

    @Test
    void budgetIsSpentTopDownThenRemainingQualifiersAreRateLimited() {
        // Three fully-qualified names, budget 2 → first two promote, third is rate-limited.
        List<UniverseCandidate> ranked = List.of(
                cand("A", 40, 3, 4), cand("B", 38, 3, 4), cand("C", 36, 3, 4));
        List<Verdict> v = evaluator.evaluateAll(ranked, Set.of(), Set.of(), id -> true, 0);
        assertEquals(Outcome.PROMOTE, v.get(0).outcome());
        assertEquals(Outcome.PROMOTE, v.get(1).outcome());
        assertEquals(Outcome.RATE_LIMITED, v.get(2).outcome());
    }

    @Test
    void nonQualifyingNamesDoNotConsumeBudget() {
        // B fails (single source) so it spends nothing; C then still gets the second promotion slot.
        List<UniverseCandidate> ranked = List.of(
                cand("A", 40, 3, 4),   // promote (1 spent)
                cand("B", 40, 1, 4),   // NOT_CORROBORATED — no budget spent
                cand("C", 40, 3, 4),   // promote (2 spent)
                cand("D", 40, 3, 4));  // RATE_LIMITED
        List<Verdict> v = evaluator.evaluateAll(ranked, Set.of(), Set.of(), id -> true, 0);
        assertEquals(Outcome.PROMOTE, v.get(0).outcome());
        assertEquals(Outcome.NOT_CORROBORATED, v.get(1).outcome());
        assertEquals(Outcome.PROMOTE, v.get(2).outcome());
        assertEquals(Outcome.RATE_LIMITED, v.get(3).outcome());
    }

    @Test
    void alreadyUsedBudgetIsRespected() {
        // One promotion already granted today → only one slot left.
        List<UniverseCandidate> ranked = List.of(cand("A", 40, 3, 4), cand("B", 40, 3, 4));
        List<Verdict> v = evaluator.evaluateAll(ranked, Set.of(), Set.of(), id -> true, 1);
        assertEquals(Outcome.PROMOTE, v.get(0).outcome());
        assertEquals(Outcome.RATE_LIMITED, v.get(1).outcome());
    }
}
