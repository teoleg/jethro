package io.jethro.app.discovery;

import java.util.Set;
import java.util.function.Predicate;

/**
 * The conservative promotion gate of ADR-0060: decides whether a discovery {@link UniverseCandidate}
 * may be promoted into the bounded, monitor-only tracked set. PURE and side-effect free — no I/O, no
 * refdata writes, no clock — so it is exactly unit-testable and the same verdict drives both the
 * dry-run proposer (Phase 1) and the real write path (Phase 2).
 *
 * <p>A candidate is promoted only if it clears ALL gates, checked in a fixed order so the reported
 * reason is the FIRST failing one (cheapest / most decisive first):
 * <ol>
 *   <li>not blacklisted (a hard human ban wins over everything);</li>
 *   <li>not already tracked (nothing to do);</li>
 *   <li>score ≥ minScore (credibility-weighted cross-source rank);</li>
 *   <li>sustained — mentioned on ≥ minSustainedDays distinct calendar days (not a one-day burst);</li>
 *   <li>corroborated — ≥ minSources distinct credible sources name it;</li>
 *   <li>feed-coverage confirmed — a configured provider can actually mark it (never admit an
 *       unmarkable name → the phantom-flat bug class);</li>
 *   <li>within the daily promotion budget — checked LAST so the budget is spent only on names that
 *       otherwise fully qualify.</li>
 * </ol>
 *
 * <p>The thresholds gate admission to the monitored set (and, later, to trading), so they are
 * money/risk-adjacent: their values live in config with {@code PLACEHOLDER — Oleg} provenance
 * (CLAUDE.md money-dial rule), never chosen here.
 */
public final class UniversePromotionPolicy {

    /** Why a candidate was or was not promoted. */
    public enum Outcome {
        PROMOTE,
        BLACKLISTED,
        ALREADY_TRACKED,
        LOW_SCORE,
        NOT_SUSTAINED,
        NOT_CORROBORATED,
        NO_FEED_COVERAGE,
        RATE_LIMITED
    }

    /** Gate thresholds. All conservative and Oleg-owned (ADR-0060); this record only validates them. */
    public record Thresholds(double minScore, int minSustainedDays, int minSources, int maxPromotionsPerDay) {
        public Thresholds {
            if (minScore < 0) {
                throw new IllegalArgumentException("minScore must be >= 0");
            }
            if (minSustainedDays < 1) {
                throw new IllegalArgumentException("minSustainedDays must be >= 1");
            }
            if (minSources < 1) {
                throw new IllegalArgumentException("minSources must be >= 1");
            }
            if (maxPromotionsPerDay < 0) {
                throw new IllegalArgumentException("maxPromotionsPerDay must be >= 0");
            }
        }
    }

    /**
     * The world the gate reads, all supplied by the caller (never fetched here).
     *
     * @param tracked            instrumentIds already in the universe (never re-promote)
     * @param blacklist          instrumentIds a human has banned (never admit)
     * @param feedCovered        true iff a configured provider can mark the given instrumentId
     * @param promotionsUsedToday promotions already granted this session-day (for the daily budget)
     */
    public record Context(Set<String> tracked, Set<String> blacklist, Predicate<String> feedCovered,
                          int promotionsUsedToday) {
    }

    /** The gate's decision for one candidate: an outcome, and a human-readable reason for the audit row. */
    public record Verdict(String instrumentId, Outcome outcome, String reason) {
        public boolean promote() {
            return outcome == Outcome.PROMOTE;
        }
    }

    private final Thresholds t;

    public UniversePromotionPolicy(Thresholds thresholds) {
        this.t = thresholds;
    }

    public Thresholds thresholds() {
        return t;
    }

    /** Evaluate one candidate against the gate. Pure: same inputs → same verdict. */
    public Verdict evaluate(UniverseCandidate c, Context ctx) {
        String id = c.instrumentId();
        if (ctx.blacklist().contains(id)) {
            return reject(id, Outcome.BLACKLISTED, "blacklisted — never admit");
        }
        if (ctx.tracked().contains(id)) {
            return reject(id, Outcome.ALREADY_TRACKED, "already in the tracked universe");
        }
        if (c.score() < t.minScore()) {
            return reject(id, Outcome.LOW_SCORE,
                    "score %.1f < %.1f required".formatted(c.score(), t.minScore()));
        }
        if (c.distinctDays() < t.minSustainedDays()) {
            return reject(id, Outcome.NOT_SUSTAINED,
                    "seen on %d day(s) < %d required (not sustained)".formatted(c.distinctDays(), t.minSustainedDays()));
        }
        if (c.sources().size() < t.minSources()) {
            return reject(id, Outcome.NOT_CORROBORATED,
                    "%d source(s) < %d required (not corroborated)".formatted(c.sources().size(), t.minSources()));
        }
        if (!ctx.feedCovered().test(id)) {
            return reject(id, Outcome.NO_FEED_COVERAGE, "no configured provider can mark it");
        }
        if (ctx.promotionsUsedToday() >= t.maxPromotionsPerDay()) {
            return reject(id, Outcome.RATE_LIMITED,
                    "daily promotion budget (%d) already spent".formatted(t.maxPromotionsPerDay()));
        }
        return new Verdict(id, Outcome.PROMOTE,
                "clears all gates: score %.1f, %d distinct day(s), %d source(s), feed-covered"
                        .formatted(c.score(), c.distinctDays(), c.sources().size()));
    }

    private static Verdict reject(String id, Outcome outcome, String reason) {
        return new Verdict(id, outcome, reason);
    }
}
