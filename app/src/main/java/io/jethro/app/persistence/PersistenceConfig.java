package io.jethro.app.persistence;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Shared persistence for all DB-backed modules (reference-data, order): one
 * DataSource, one Flyway run that applies every module's migrations from
 * classpath:db/migration (ADR-0005/0013). Gated by jethro.persistence.enabled so
 * DB-less unit tests and partial dev setups run without Postgres. Spring's DataSource
 * and Flyway auto-config are excluded (see application.properties).
 */
@Configuration
@ConditionalOnProperty(prefix = "jethro.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PersistenceConfig {

    @Bean(destroyMethod = "close")
    HikariDataSource dataSource(
            @Value("${jethro.datasource.url}") String url,
            @Value("${jethro.datasource.username}") String username,
            @Value("${jethro.datasource.password}") String password) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setMaximumPoolSize(8);
        return ds;
    }

    @Bean
    Flyway flyway(HikariDataSource dataSource) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();
        flyway.migrate(); // schema is code: all modules' migrations run on startup
        return flyway;
    }

    /** Depends on flyway so migrations complete before any repository queries. */
    @Bean
    JdbcTemplate jdbcTemplate(HikariDataSource dataSource, Flyway flyway) {
        return new JdbcTemplate(dataSource);
    }
}
