package io.jethro.app.discovery;

import java.util.Set;

/**
 * A candidate addition to the tracked universe (ADR-0050 §7): an instrument we do NOT currently track
 * that credible sources are talking about. A SUGGESTION only — nothing here adds an instrument or
 * places an order; a human decides whether to promote it to the base list.
 *
 * @param instrumentId   the discovered ticker (not in reference data)
 * @param score          credibility-weighted, cross-source rank (higher = more worth a look)
 * @param mentions       total mentions accumulated in the window
 * @param sources        distinct sources naming it (e.g. reuters, stocktwits) — cross-source matters
 * @param firstSeenMillis when it first appeared
 * @param lastSeenMillis  most recent mention
 * @param sample         a representative headline/post
 */
public record UniverseCandidate(String instrumentId, double score, int mentions, Set<String> sources,
                                long firstSeenMillis, long lastSeenMillis, String sample) {
}
