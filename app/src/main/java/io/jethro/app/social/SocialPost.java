package io.jethro.app.social;

/**
 * One social-media post ingested for advisory context (ADR-0050). Social media is an ADVERSARIAL
 * source — the credibility signals here ({@code verified}/{@code followers}/{@code accountAgeDays})
 * and the channel's registry tier drive the deterministic spam pre-filter and the corroboration gate.
 * Nothing here is a number fed into sizing/risk (ADR-0016, invariant 7); the text is read for context
 * only, and no social-derived signal can ever originate an order (ADR-0049).
 */
public record SocialPost(String id, String source, String channel, String author, int followers,
                         boolean verified, int accountAgeDays, long timestampMillis, String text) {
}
