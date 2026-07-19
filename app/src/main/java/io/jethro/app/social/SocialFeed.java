package io.jethro.app.social;

import java.util.List;

/**
 * The social-post source (ADR-0050) — ALWAYS REAL DATA, with NO relation to the market-data sim.
 * Implementations are the real free adapters ({@link StockTwitsSocialFeed}, {@link TelegramSocialFeed})
 * composed by {@link CompositeSocialFeed}; a synthetic generator exists ONLY as a test double, never
 * wired at runtime. Nothing here is a number fed into sizing/risk (invariant 7); the text is read for
 * advisory context only, and no social signal can originate an order (ADR-0049).
 */
public interface SocialFeed {

    /** Recent posts to process this cycle. {@code equityUniverse} are the ids a post may reference. */
    List<SocialPost> poll(List<String> equityUniverse, long nowMillis);

    /** Per-source connection health for the UI (ADR-0050). A single source returns one status; the
     *  composite flattens its children. Default: nothing to report. */
    default List<SocialSourceStatus> health() {
        return List.of();
    }
}
