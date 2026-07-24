package io.jethro.app.discovery;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Applies the {@link UniversePromotionPolicy} across a whole ranked candidate list, threading the daily
 * promotion budget down the ranking (each hypothetical promotion spends one, so lower-ranked names that
 * would otherwise qualify surface as RATE_LIMITED). PURE and side-effect free — the same evaluation
 * drives the read-only proposals endpoint and the daily proposer lifecycle (ADR-0060 Phase 1).
 */
public final class UniversePromotionEvaluator {

    private final UniversePromotionPolicy policy;

    public UniversePromotionEvaluator(UniversePromotionPolicy policy) {
        this.policy = policy;
    }

    public UniversePromotionPolicy policy() {
        return policy;
    }

    /**
     * Evaluate every candidate in rank order.
     *
     * @param ranked               candidates, best first (the register's own ranking)
     * @param tracked              instrumentIds already in the universe
     * @param blacklist            banned instrumentIds
     * @param feedCovered          true iff a configured provider can mark the instrumentId
     * @param alreadyPromotedToday promotions already granted this session-day (0 in Phase 1 dry-run)
     * @return one verdict per candidate, in the same order
     */
    public List<UniversePromotionPolicy.Verdict> evaluateAll(
            List<UniverseCandidate> ranked, Set<String> tracked, Set<String> blacklist,
            Predicate<String> feedCovered, int alreadyPromotedToday) {
        List<UniversePromotionPolicy.Verdict> out = new ArrayList<>(ranked.size());
        int used = Math.max(0, alreadyPromotedToday);
        for (UniverseCandidate c : ranked) {
            UniversePromotionPolicy.Verdict v =
                    policy.evaluate(c, new UniversePromotionPolicy.Context(tracked, blacklist, feedCovered, used));
            if (v.promote()) {
                used++;
            }
            out.add(v);
        }
        return out;
    }
}
