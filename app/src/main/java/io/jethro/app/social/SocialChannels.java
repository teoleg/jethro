package io.jethro.app.social;

import java.util.Map;

/**
 * Curated channel registry (ADR-0050 §2/§4): each channel carries a credibility TIER that caps its
 * advisory weight. The tier is <b>categorical</b> (ADR-0016) — never a number multiplying a position.
 * Selection starts curated; the ADR-0027 outcome-scored ranking that promotes/demotes channels by
 * measured hit-rate (and channel discovery) is a follow-up — we do not guess forever, we measure.
 */
public final class SocialChannels {

    public enum Tier { TRUSTED, STANDARD, UNTRUSTED }

    private final Map<String, Tier> tiers;
    private final Tier defaultTier;
    private final int credibleFollowerFloor;
    private final int credibleAgeDaysFloor;

    /** Curated-only: unknown channels are UNTRUSTED (guilty until curated) — the sim default. */
    public SocialChannels(Map<String, Tier> tiers, int credibleFollowerFloor, int credibleAgeDaysFloor) {
        this(tiers, Tier.UNTRUSTED, credibleFollowerFloor, credibleAgeDaysFloor);
    }

    /**
     * @param defaultTier the tier for a channel not in the registry. UNTRUSTED (never credible) suits
     *   a fully-curated set (the sim); a real feed (StockTwits/Telegram) where you cannot curate every
     *   organic account sets it to STANDARD so unknown accounts are judged by the author FLOORS below
     *   — a pump throwaway (unverified / few followers / brand-new) still fails and cannot corroborate.
     */
    public SocialChannels(Map<String, Tier> tiers, Tier defaultTier, int credibleFollowerFloor,
                          int credibleAgeDaysFloor) {
        this.tiers = Map.copyOf(tiers);
        this.defaultTier = defaultTier;
        this.credibleFollowerFloor = credibleFollowerFloor;
        this.credibleAgeDaysFloor = credibleAgeDaysFloor;
    }

    /** Registry tier for a channel; an unknown channel gets the configured default tier. */
    public Tier tierOf(String channel) {
        return tiers.getOrDefault(channel, defaultTier);
    }

    /**
     * A post is CREDIBLE (may contribute to corroboration) when its channel is TRUSTED, or — for a
     * STANDARD channel — the author carries a credential: either the PLATFORM asserts the identity
     * ({@code verified}), or the account clears BOTH measured floors (enough followers, not a
     * brand-new throwaway). An UNTRUSTED channel, or an anonymous new account, is the pump-and-dump
     * profile and is never credible; such posts can flag manipulation but never promote a signal.
     *
     * <p><b>Why the two credentials are ALTERNATIVES, not a conjunction (ADR-0139).</b> Requiring both
     * makes the floors unreachable on any platform that issues no verification, which is every organic
     * feed wired here: the StockTwits adapter populates {@code verified} from {@code user.official},
     * which marks StockTwits' OWN corporate accounts and is false for every organic author — so
     * {@code verified && …} short-circuited before either floor was evaluated and NO StockTwits post
     * could corroborate anything. That is the opposite of what the {@code defaultTier} javadoc above
     * promises ("unknown accounts are judged by the author FLOORS below"), and it silently gagged the
     * desk's only source with positive measured expectancy at every horizon. Both floors keep their
     * configured values and the ADR-0050 §3 threshold {@code k} is unchanged, so a throwaway (few
     * followers, brand-new) still fails exactly as before; the change is one-way — nothing credible
     * before this is non-credible after it.
     */
    public boolean isCredible(SocialPost p) {
        return switch (tierOf(p.channel())) {
            case TRUSTED -> true;
            case UNTRUSTED -> false;
            case STANDARD -> p.verified()
                    || (p.followers() >= credibleFollowerFloor && p.accountAgeDays() >= credibleAgeDaysFloor);
        };
    }
}
