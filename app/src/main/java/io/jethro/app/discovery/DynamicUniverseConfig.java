package io.jethro.app.discovery;

import io.jethro.trading.riskpnl.InstrumentRefSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires the dynamic discovery-driven universe (ADR-0060). The pure gate (policy + evaluator), the audit
 * repository, and the read-only proposals controller are always present, so {@code /api/universe/proposals}
 * works even when the feature is off. The active daily proposer {@link UniversePromotionLifecycle} is gated
 * on {@code jethro.universe.dynamic.enabled} (default false) — Phase 1 is dry-run: it decides and audits
 * but never writes reference data or trades.
 */
@Configuration
@EnableConfigurationProperties(DynamicUniverseProperties.class)
public class DynamicUniverseConfig {

    @Bean
    UniversePromotionPolicy universePromotionPolicy(DynamicUniverseProperties props) {
        return new UniversePromotionPolicy(props.toThresholds());
    }

    @Bean
    UniversePromotionEvaluator universePromotionEvaluator(UniversePromotionPolicy policy) {
        return new UniversePromotionEvaluator(policy);
    }

    @Bean
    @ConditionalOnProperty(prefix = "jethro.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
    UniversePromotionRepository universePromotionRepository(JdbcTemplate jdbc) {
        return new UniversePromotionRepository(jdbc);
    }

    @Bean
    UniversePromotionController universePromotionController(ObjectProvider<UniversePromotionLifecycle> lifecycle,
                                                           ObjectProvider<UniversePromotionRepository> repository) {
        return new UniversePromotionController(lifecycle, repository);
    }

    @Bean
    @ConditionalOnProperty(prefix = "jethro.universe.dynamic", name = "enabled", havingValue = "true")
    UniversePromotionLifecycle universePromotionLifecycle(UniverseCandidates candidates,
                                                          UniversePromotionEvaluator evaluator,
                                                          InstrumentRefSource refs, CompanyDirectory companies,
                                                          DynamicUniverseProperties props,
                                                          ObjectProvider<UniversePromotionRepository> audit) {
        return new UniversePromotionLifecycle(candidates, evaluator, refs, companies, props, audit);
    }
}
