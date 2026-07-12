package io.jethro.app.refdata;

import com.zaxxer.hikari.HikariDataSource;
import io.jethro.refdata.RefDataController;
import io.jethro.refdata.RefDataRepository;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Reference-data wiring (ADR-0015: assembly wires modules). DataSource and Flyway are
 * configured manually behind jethro.refdata.enabled — Spring's auto-config is excluded
 * so broker-less/db-less unit tests and partial dev setups keep working.
 */
@Configuration
@ConditionalOnProperty(prefix = "jethro.refdata", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RefDataConfig {

    @Bean(destroyMethod = "close")
    HikariDataSource refDataSource(
            @Value("${jethro.datasource.url}") String url,
            @Value("${jethro.datasource.username}") String username,
            @Value("${jethro.datasource.password}") String password) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setMaximumPoolSize(5);
        return ds;
    }

    @Bean
    Flyway flyway(HikariDataSource dataSource) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();
        flyway.migrate(); // schema is code: migrations run on startup (ADR-0005/0013)
        return flyway;
    }

    @Bean
    RefDataRepository refDataRepository(HikariDataSource dataSource, Flyway flyway) {
        return new RefDataRepository(new JdbcTemplate(dataSource));
    }

    @Bean
    RefDataController refDataController(RefDataRepository repository) {
        return new RefDataController(repository);
    }
}
