package io.jethro.app.social;

/**
 * An advisory subject the corroboration gate surfaced (ADR-0050). It is CONTEXT only — it never sizes
 * a trade or originates an order; any downstream trade candidate still passes the ADR-0049 hard gate.
 *
 * @param instrumentId          the security (resolved from a cashtag/mention) — may NOT be tracked
 * @param tracked               true if it is in our configured reference-data universe; false = a
 *                              DISCOVERED name social is discussing but we don't track (a suggestion
 *                              to add — what to do with it is a later choice, not a filter)
 * @param sector                its GICS sector via {@code hedge_group} (ADR-0040) if tracked, else "untracked"
 * @param direction             BULLISH/BEARISH/NEUTRAL from a deterministic lexicon majority
 * @param corroboratingChannels distinct CREDIBLE channels behind it (≥ k when promoted)
 * @param mentions              total posts mentioning it this window
 * @param manipulationSuspected true when a high-mention burst FAILED corroboration and is dominated by
 *                              low-credibility posts — a pump-and-dump tell, surfaced, never traded
 * @param sample                one representative post text
 */
public record SocialSignal(String instrumentId, boolean tracked, String sector, String direction,
                           int corroboratingChannels, int mentions, boolean manipulationSuspected,
                           String sample) {
}
