package io.jethro.app.risk;

import io.jethro.app.trading.SimHistorySeeder;
import io.jethro.app.trading.TradingCoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Daily-return history status + boot seed (ADR-0038). DB-backed, so gated on persistence; the
 * seeder is SIM-only and idempotent. Seeding depends on the provenance (feed mode) being resolved
 * first, so the SIM gate reads the right mode.
 */
@Configuration
@ConditionalOnProperty(prefix = "jethro.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
public class HistoryConfig {

    @Bean
    HistoryStatus historyStatus(JdbcTemplate jdbc) {
        return new HistoryStatus(jdbc);
    }

    @Bean
    @DependsOn("provenanceConfig")
    SimHistorySeeder simHistorySeeder(JdbcTemplate jdbc, TradingCoreProperties props, HistoryStatus status,
                                      @Value("${jethro.hedge.history-seed-days:60}") int windowDays) {
        var seeder = new SimHistorySeeder(jdbc, props, status, windowDays);
        seeder.start(); // no-op unless SIM mode and daily_close is under a full window
        return seeder;
    }
}
