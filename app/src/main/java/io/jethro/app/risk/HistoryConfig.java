package io.jethro.app.risk;

import io.jethro.app.trading.HistorySeeder;
import io.jethro.app.trading.TiingoHistoryClient;
import io.jethro.app.trading.TradingCoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;

/**
 * Daily-return history status + boot seed (ADR-0038). DB-backed, so gated on persistence. The
 * seeder pulls REAL history from Tiingo (free, API-key gated — the Yahoo channel is defunct), even in
 * sim mode, on a background thread, so it never blocks boot and a missing token / network failure is
 * harmless (the covariance just warms from the live feed).
 */
@Configuration
@ConditionalOnProperty(prefix = "jethro.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
public class HistoryConfig {

    @Bean
    HistoryStatus historyStatus(JdbcTemplate jdbc) {
        return new HistoryStatus(jdbc);
    }

    @Bean(destroyMethod = "stop")
    @ConditionalOnProperty(prefix = "jethro.hedge", name = "history-seed", havingValue = "tiingo", matchIfMissing = true)
    HistorySeeder historySeeder(JdbcTemplate jdbc, TradingCoreProperties props,
                                io.jethro.trading.riskpnl.InstrumentRefSource refs, HistoryStatus status,
                                @Value("${jethro.hedge.tiingo-token:}") String token,
                                @Value("${jethro.hedge.history-seed-days:60}") int windowDays,
                                @Value("${jethro.hedge.history-range-years:5}") int years,
                                @Value("${jethro.hedge.history-request-spacing-millis:800}") long spacingMillis,
                                @Value("${jethro.hedge.history-refresh-hours:24}") int refreshHours) {
        var client = new TiingoHistoryClient(token, Duration.ofSeconds(15), years);
        var seeder = new HistorySeeder(jdbc, props, refs, status, client, windowDays, spacingMillis, refreshHours);
        seeder.start(); // background thread; no-op-ish when a full window already exists / no token
        return seeder;
    }
}
