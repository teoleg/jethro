package io.jethro.app.risk;

import io.jethro.app.trading.TradingCoreProperties;
import io.jethro.app.trading.YahooHistoryClient;
import io.jethro.app.trading.YahooHistorySeeder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;

/**
 * Daily-return history status + boot seed (ADR-0038). DB-backed, so gated on persistence. The
 * seeder pulls REAL history from the existing Yahoo channel — even in sim mode — on a background
 * thread, so it never blocks boot and a network failure is harmless.
 */
@Configuration
@ConditionalOnProperty(prefix = "jethro.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
public class HistoryConfig {

    @Bean
    HistoryStatus historyStatus(JdbcTemplate jdbc) {
        return new HistoryStatus(jdbc);
    }

    @Bean(destroyMethod = "stop")
    @ConditionalOnProperty(prefix = "jethro.hedge", name = "history-seed", havingValue = "yahoo", matchIfMissing = true)
    YahooHistorySeeder yahooHistorySeeder(JdbcTemplate jdbc, TradingCoreProperties props, HistoryStatus status,
                                          @Value("${jethro.hedge.history-seed-days:60}") int windowDays,
                                          @Value("${jethro.hedge.history-range:5y}") String range,
                                          @Value("${jethro.hedge.history-request-spacing-millis:800}") long spacingMillis) {
        var client = new YahooHistoryClient(Duration.ofSeconds(15), range);
        var seeder = new YahooHistorySeeder(jdbc, props, status, client, windowDays, spacingMillis);
        seeder.start(); // background thread; no-op-ish when a full window already exists
        return seeder;
    }
}
