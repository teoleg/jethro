package io.jethro.app.social;

import io.jethro.app.trading.TradingCoreProperties;
import io.jethro.trading.riskpnl.InstrumentRefSource;
import io.jethro.uigateway.AttentionFeed;
import io.jethro.uigateway.SseBroadcaster;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wires the ADR-0050 social source. Gated on {@code jethro.social.enabled} (default true). Phase 1 is
 * the seedable SIM feed only — synthetic posts incl. spam + a pump — advisory-only and never an order
 * (ADR-0049); real free adapters (StockTwits/Telegram) are Phase 2 behind a live gate.
 */
@Configuration
@EnableConfigurationProperties(SocialProperties.class)
@ConditionalOnProperty(prefix = "jethro.social", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SocialConfig {

    // News outlets flow through the pipeline as credible channels (ADR-0050 merge): central banks /
    // the regulator / the stats agency are TRUSTED (authoritative); market media are STANDARD (credible
    // but judged like an organic account). Keyed on the jethro.discovery.outlets names; override any of
    // these in jethro.social.channel-tiers.
    private static final Map<String, String> NEWS_TIERS = Map.of(
            "fed", "TRUSTED", "sec", "TRUSTED", "boe", "TRUSTED", "bls", "TRUSTED",
            "cnbc", "STANDARD", "yahoo", "STANDARD");

    @Bean
    SocialChannels socialChannels(SocialProperties props) {
        Map<String, SocialChannels.Tier> tiers = new LinkedHashMap<>();
        NEWS_TIERS.forEach((channel, tier) -> tiers.put(channel, SocialChannels.Tier.valueOf(tier)));
        // Explicit config wins over the news defaults above.
        props.channelTiersOrDefault().forEach((channel, tier) ->
                tiers.put(channel, SocialChannels.Tier.valueOf(tier.trim().toUpperCase())));
        return new SocialChannels(tiers, props.defaultTierOrDefault(),
                props.followerFloorOrDefault(), props.ageFloorOrDefault());
    }

    @Bean
    SocialFeed socialFeed(SocialProperties props,
                          org.springframework.beans.factory.ObjectProvider<io.jethro.app.discovery.DiscoveryLifecycle> discovery) {
        // REAL sources only (ADR-0050) — social media is an always-online external source with NO
        // relation to the market-data sim. There is deliberately no synthetic runtime feed; a failing
        // source never sinks the cycle and shows "unreachable" on the Sources page.
        List<SocialFeed> sources = new ArrayList<>();
        for (String s : props.sourcesOrDefault()) {
            switch (s.trim().toLowerCase()) {
                case "stocktwits" -> sources.add(new StockTwitsSocialFeed(
                        props.stocktwitsBaseUrlOrDefault(), props.stocktwitsSymbolsPerCycleOrDefault(),
                        Duration.ofSeconds(8)));
                case "telegram" -> sources.add(new TelegramSocialFeed(
                        props.telegramBaseUrlOrDefault(), props.telegramBotTokenOrEmpty(), Duration.ofSeconds(8)));
                default -> { /* unknown/removed source name (e.g. 'sim') — ignored */ }
            }
        }
        if (sources.isEmpty()) {
            // No source configured → an idle StockTwits so the pipeline + Sources page still run.
            sources.add(new StockTwitsSocialFeed(props.stocktwitsBaseUrlOrDefault(),
                    props.stocktwitsSymbolsPerCycleOrDefault(), Duration.ofSeconds(8)));
        }
        // RSS news joins the SAME pipeline (ADR-0050 merge): its already-fetched headlines become
        // credible posts, so news + social cross-corroborate. Reuses discovery's fetch (no extra HTTP);
        // idle when discovery is disabled. Kept as an extra source so social config is unchanged.
        sources.add(new NewsSocialFeed(discovery));
        return sources.size() == 1 ? sources.get(0) : new CompositeSocialFeed(sources);
    }

    @Bean
    SpamFilter socialSpamFilter(SocialProperties props) {
        return new SpamFilter(props.maxCashtagsOrDefault());
    }

    @Bean
    SocialLifecycle socialLifecycle(SocialFeed feed, SocialChannels channels, SpamFilter spamFilter,
                                    InstrumentRefSource refs, AttentionFeed attention,
                                    SseBroadcaster sse, SocialProperties props,
                                    org.springframework.beans.factory.ObjectProvider<io.jethro.app.discovery.UniverseCandidates> candidates,
                                    @org.springframework.beans.factory.annotation.Value("${jethro.discovery.social-weight:1.0}") double discoverySocialWeight) {
        // The discovery register (ADR-0050 §7) is present only when jethro.discovery.enabled — social
        // feeds untracked corroborated names into it; without it, social runs unchanged.
        return new SocialLifecycle(feed, channels, spamFilter, refs, attention, sse, props,
                candidates.getIfAvailable(), discoverySocialWeight);
    }
}
