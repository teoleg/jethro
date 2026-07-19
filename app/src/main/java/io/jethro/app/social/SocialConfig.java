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

    @Bean
    SocialChannels socialChannels(SocialProperties props) {
        Map<String, SocialChannels.Tier> tiers = new LinkedHashMap<>();
        props.channelTiersOrDefault().forEach((channel, tier) ->
                tiers.put(channel, SocialChannels.Tier.valueOf(tier.trim().toUpperCase())));
        return new SocialChannels(tiers, props.defaultTierOrDefault(),
                props.followerFloorOrDefault(), props.ageFloorOrDefault());
    }

    @Bean
    SocialFeed socialFeed(SocialProperties props) {
        // Compose the configured sources (ADR-0050 Phase 2). Default [sim] — a real adapter is opt-in
        // and the app never depends on the network at boot; a failing source never sinks the cycle.
        List<SocialFeed> sources = new ArrayList<>();
        for (String s : props.sourcesOrDefault()) {
            switch (s.trim().toLowerCase()) {
                case "sim" -> sources.add(new SimSocialFeed(props.seedOrDefault()));
                case "stocktwits" -> sources.add(new StockTwitsSocialFeed(
                        props.stocktwitsBaseUrlOrDefault(), props.stocktwitsSymbolsPerCycleOrDefault(),
                        Duration.ofSeconds(8)));
                case "telegram" -> sources.add(new TelegramSocialFeed(
                        props.telegramBaseUrlOrDefault(), props.telegramBotTokenOrEmpty(), Duration.ofSeconds(8)));
                default -> { /* unknown source name — ignored */ }
            }
        }
        if (sources.isEmpty()) {
            sources.add(new SimSocialFeed(props.seedOrDefault()));
        }
        return sources.size() == 1 ? sources.get(0) : new CompositeSocialFeed(sources);
    }

    @Bean
    SpamFilter socialSpamFilter(SocialProperties props) {
        return new SpamFilter(props.maxCashtagsOrDefault());
    }

    @Bean
    SocialLifecycle socialLifecycle(SocialFeed feed, SocialChannels channels, SpamFilter spamFilter,
                                    InstrumentRefSource refs, TradingCoreProperties sim,
                                    AttentionFeed attention, SseBroadcaster sse, SocialProperties props) {
        return new SocialLifecycle(feed, channels, spamFilter, refs, sim, attention, sse, props);
    }
}
