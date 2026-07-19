package io.jethro.app.social;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
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
        Map<String, String> channelTiers,
        /** Active sources (ADR-0050 Phase 2): any of {@code sim, stocktwits}. Default [sim] — a real
         *  adapter is opt-in and the app never depends on the network at boot. */
        List<String> sources,
        /** Tier for a channel not in the registry. UNTRUSTED (sim-safe) by default; set STANDARD when
         *  a real feed is on so organic accounts are judged by the credibility floors. */
        String defaultTier,
        /** StockTwits API base (public streams). */
        String stocktwitsBaseUrl,
        /** Symbols polled per cycle (round-robined over the universe) — a rate-limit guard. */
        int stocktwitsSymbolsPerCycle,
        /** Telegram Bot API base. */
        String telegramBaseUrl,
        /** Telegram bot token (from env, e.g. ${TELEGRAM_BOT_TOKEN}); blank → the source is idle. */
        String telegramBotToken) {

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

    public List<String> sourcesOrDefault() {
        return sources != null && !sources.isEmpty() ? sources : List.of("sim");
    }

    public SocialChannels.Tier defaultTierOrDefault() {
        return defaultTier != null && !defaultTier.isBlank()
                ? SocialChannels.Tier.valueOf(defaultTier.trim().toUpperCase())
                : SocialChannels.Tier.UNTRUSTED;
    }

    public String stocktwitsBaseUrlOrDefault() {
        return stocktwitsBaseUrl != null && !stocktwitsBaseUrl.isBlank()
                ? stocktwitsBaseUrl : "https://api.stocktwits.com/api/2";
    }

    public int stocktwitsSymbolsPerCycleOrDefault() {
        return stocktwitsSymbolsPerCycle > 0 ? stocktwitsSymbolsPerCycle : 3;
    }

    public String telegramBaseUrlOrDefault() {
        return telegramBaseUrl != null && !telegramBaseUrl.isBlank() ? telegramBaseUrl : "https://api.telegram.org";
    }

    public String telegramBotTokenOrEmpty() {
        return telegramBotToken == null ? "" : telegramBotToken.trim();
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
