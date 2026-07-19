package io.jethro.app.social;

import java.util.List;

/**
 * The social-post source (ADR-0050). Two eventual implementations — {@link SimSocialFeed} (seedable
 * synthetic posts incl. spam + a pump, the offline/dev default) and real free adapters (StockTwits,
 * Telegram public channels — Phase 2, behind a live gate) — so a real feed slots in without touching
 * the pipeline (the ADR-0009 spirit). Nothing here is a number fed into sizing/risk (invariant 7);
 * the text is read for advisory context only, and no social signal can originate an order (ADR-0049).
 */
public interface SocialFeed {

    /** Recent posts to process this cycle. {@code equityUniverse} are the ids a post may reference. */
    List<SocialPost> poll(List<String> equityUniverse, long nowMillis);
}
