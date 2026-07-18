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
                                              ObjectProvider<io.jethro.app.order.ExecutionProperties> executionCosts,
                                              ObjectProvider<org.springframework.jdbc.core.JdbcTemplate> jdbc) {
        // RefData present only when persistence is on; needed to map yahoo symbols (ADR-0023).
        // Execution costs feed the sim's quote synthesis (ADR-0025); defaults when order module is off.
        // A durable quarantine store when the DB is present, so a corporate-action freeze survives
        // a restart (GAP-2); NONE (memory-only, pre-existing behaviour) without persistence.
        var template = jdbc.getIfAvailable();
        io.jethro.trading.runtime.MarkCache.QuarantineStore quarantine = template != null
                ? new JdbcMarkQuarantineStore(template)
                : io.jethro.trading.runtime.MarkCache.QuarantineStore.NONE;
        return new TradingCoreLifecycle(properties, refData.getIfAvailable(), rateLimiter,
                executionCosts.getIfAvailable(() -> new io.jethro.app.order.ExecutionProperties(null, null, null)),
                quarantine);
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

    /** Timestamped price/volume/news spike feed for the sim page (correlate to orders + logs). */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "jethro.trading", name = "enabled", havingValue = "true", matchIfMissing = true)
    SpikeMonitor spikeMonitor(TradingCoreLifecycle tradingCore) {
        var monitor = new SpikeMonitor(tradingCore, 40.0, 2.0, 1.3);
        monitor.start();
        return monitor;
    }
}
