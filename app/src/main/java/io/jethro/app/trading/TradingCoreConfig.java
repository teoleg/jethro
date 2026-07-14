package io.jethro.app.trading;

import io.jethro.refdata.RefDataRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TradingCoreProperties.class)
public class TradingCoreConfig {

    /** One shared budget for ALL Finnhub REST calls (news + yield curve) — the free tier's
     *  60/min limit is account-wide, so a single limiter injected everywhere is the correct
     *  guard (ADR-0024). The trade WebSocket is exempt and doesn't pass through this. */
    @Bean
    FinnhubRateLimiter finnhubRateLimiter(TradingCoreProperties properties) {
        return new FinnhubRateLimiter(properties.finnhubMaxCallsPerMinuteOrDefault());
    }

    @Bean
    @ConditionalOnProperty(prefix = "jethro.trading", name = "enabled", havingValue = "true", matchIfMissing = true)
    TradingCoreLifecycle tradingCoreLifecycle(TradingCoreProperties properties,
                                              ObjectProvider<RefDataRepository> refData,
                                              FinnhubRateLimiter rateLimiter,
                                              ObjectProvider<io.jethro.app.order.ExecutionProperties> executionCosts) {
        // RefData present only when persistence is on; needed to map yahoo symbols (ADR-0023).
        // Execution costs feed the sim's quote synthesis (ADR-0025); defaults when order module is off.
        return new TradingCoreLifecycle(properties, refData.getIfAvailable(), rateLimiter,
                executionCosts.getIfAvailable(() -> new io.jethro.app.order.ExecutionProperties(null, null, null)));
    }

    /** Quarantined-mark ALERT cards (corporate action / bad print) — deterministic floor. */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "jethro.trading", name = "enabled", havingValue = "true", matchIfMissing = true)
    MarkQuarantineMonitor markQuarantineMonitor(TradingCoreLifecycle tradingCore,
                                                io.jethro.uigateway.AttentionFeed feed,
                                                io.jethro.uigateway.SseBroadcaster sse) {
        var monitor = new MarkQuarantineMonitor(tradingCore, feed, sse);
        monitor.start();
        return monitor;
    }
}
