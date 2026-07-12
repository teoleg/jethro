package io.jethro.app.refdata;

import io.jethro.refdata.RefDataController;
import io.jethro.refdata.RefDataRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Reference-data wiring (ADR-0015). Uses the shared JdbcTemplate from
 * {@link io.jethro.app.persistence.PersistenceConfig}; gated on the same
 * jethro.persistence.enabled flag.
 */
@Configuration
@ConditionalOnProperty(prefix = "jethro.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RefDataConfig {

    @Bean
    RefDataRepository refDataRepository(JdbcTemplate jdbcTemplate) {
        return new RefDataRepository(jdbcTemplate);
    }

    @Bean
    RefDataController refDataController(RefDataRepository repository) {
        return new RefDataController(repository);
    }
}
