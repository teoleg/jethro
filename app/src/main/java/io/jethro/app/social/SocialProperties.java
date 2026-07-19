package io.jethro.app.social;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Social-source config (jethro.social, ADR-0050). Phase 1 is the SEEDABLE SIM feed only — synthetic
 * posts incl. spam + a pump — advisory-only and never an order (ADR-0049); real free adapters
 * (StockTwits/Telegram) are Phase 2 behind a live gate. The tuning knobs (k, burst, floors, cadence)
 * are scheduling/spam-defence dials, not risk/money numbers.
 */
@ConfigurationProperties(prefix = "jethro.social")
public record SocialProperties(
        boolean enabled,
        /** Poll cadence. Demo default 60s so results show today; prod ~1800 (30 min) per ADR-0045/0050. */
        long intervalSeconds,
        /** k — distinct CREDIBLE channels required to promote a subject (the corroboration threshold). */
        int corroborationChannels,
        /** Mentions at/above which an uncorroborated, low-credibility-dominated subject is flagged
         *  as suspected manipulation (a pump tell). */
        int burstThreshold,
        /** A post referencing more than this many cashtags is dropped as shill/spam. */
        int maxCashtags,
        /** STANDARD-tier credibility floors (TRUSTED bypasses; UNTRUSTED never credible). */
        int credibleFollowerFloor,
        int credibleAgeDaysFloor,
        /** Sim feed seed (reproducible). */
        long seed,
        /** Curated channel → tier (TRUSTED/STANDARD/UNTRUSTED). Seeded below when unset. */
        Map<String, String> channelTiers) {

    public long intervalSecondsOrDefault() {
        return intervalSeconds > 0 ? intervalSeconds : 60;
    }

    public int kOrDefault() {
        return corroborationChannels > 0 ? corroborationChannels : 2;
    }

    public int burstThresholdOrDefault() {
        return burstThreshold > 0 ? burstThreshold : 4;
    }

    public int maxCashtagsOrDefault() {
        return maxCashtags > 0 ? maxCashtags : 3;
    }

    public int followerFloorOrDefault() {
        return credibleFollowerFloor > 0 ? credibleFollowerFloor : 5_000;
    }

    public int ageFloorOrDefault() {
        return credibleAgeDaysFloor > 0 ? credibleAgeDaysFloor : 180;
    }

    public long seedOrDefault() {
        return seed != 0 ? seed : 42L;
    }

    /** Curated seed registry (ADR-0050 §4) matching the sim channels; unknown channels are UNTRUSTED. */
    public Map<String, String> channelTiersOrDefault() {
        if (channelTiers != null && !channelTiers.isEmpty()) {
            return channelTiers;
        }
        Map<String, String> seed = new LinkedHashMap<>();
        seed.put("wire:Reuters", "TRUSTED");
        seed.put("wire:Bloomberg", "TRUSTED");
        seed.put("st:AnalystJane", "STANDARD");
        seed.put("st:MacroMike", "STANDARD");
        return seed;
    }
}
