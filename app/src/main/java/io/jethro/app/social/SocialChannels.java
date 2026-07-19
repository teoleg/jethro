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
    private final int credibleFollowerFloor;
    private final int credibleAgeDaysFloor;

    public SocialChannels(Map<String, Tier> tiers, int credibleFollowerFloor, int credibleAgeDaysFloor) {
        this.tiers = Map.copyOf(tiers);
        this.credibleFollowerFloor = credibleFollowerFloor;
        this.credibleAgeDaysFloor = credibleAgeDaysFloor;
    }

    /** Registry tier for a channel; an unknown channel is UNTRUSTED (guilty until curated). */
    public Tier tierOf(String channel) {
        return tiers.getOrDefault(channel, Tier.UNTRUSTED);
    }

    /**
     * A post is CREDIBLE (may contribute to corroboration) when its channel is TRUSTED, or — for a
     * STANDARD channel — the author clears the floors (verified, enough followers, not a brand-new
     * throwaway). An UNTRUSTED channel, or an anonymous new account, is the pump-and-dump profile and
     * is never credible; such posts can flag manipulation but never promote a signal.
     */
    public boolean isCredible(SocialPost p) {
        return switch (tierOf(p.channel())) {
            case TRUSTED -> true;
            case UNTRUSTED -> false;
            case STANDARD -> p.verified() && p.followers() >= credibleFollowerFloor
                    && p.accountAgeDays() >= credibleAgeDaysFloor;
        };
    }
}
