package io.jethro.app.discovery;

import io.jethro.app.risk.RefDataInstrumentRefSource;
import io.jethro.refdata.RefDataRepository;
import io.jethro.trading.riskpnl.InstrumentRefSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
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
                                                           ObjectProvider<UniversePromotionRepository> repository,
                                                           ObjectProvider<UniversePromotionService> service,
                                                           DynamicUniverseProperties props) {
        return new UniversePromotionController(lifecycle, repository, service, props);
    }

    /** Runtime refdata write port (ADR-0060 Phase 2). Present only with persistence + the concrete
     *  refdata-backed ref source (so the promotion write path can refresh the cache). */
    @Bean
    @ConditionalOnProperty(prefix = "jethro.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
    UniverseRefdataGateway universeRefdataGateway(ObjectProvider<RefDataRepository> repo,
                                                  ObjectProvider<RefDataInstrumentRefSource> refSource) {
        RefDataRepository r = repo.getIfAvailable();
        RefDataInstrumentRefSource rs = refSource.getIfAvailable();
        return (r != null && rs != null) ? new RefDataUniverseGateway(r, rs) : null;
    }

    /** The Phase 2 promotion write service. Null (→ dry-run stays the only path) unless the gateway,
     *  the audit repo, and the company directory are all present. */
    @Bean
    UniversePromotionService universePromotionService(ObjectProvider<UniverseRefdataGateway> gateway,
                                                      ObjectProvider<CompanyDirectory> companies,
                                                      ObjectProvider<UniversePromotionRepository> audit,
                                                      DynamicUniverseProperties props) {
        UniverseRefdataGateway g = gateway.getIfAvailable();
        CompanyDirectory c = companies.getIfAvailable();
        UniversePromotionRepository a = audit.getIfAvailable();
        return (g != null && c != null && a != null) ? new UniversePromotionService(g, c, a, props) : null;
    }

    /** Hands-off boot healer (runs before trading-core): if an over-aggressive cross-mode cleanup evicted a
     *  large pile of names that were never re-promoted, restore them re-tagged with the current mode. Only
     *  fires for a big batch so a deliberate small prune sticks. Gated on persistence + the write path. */
    @Bean
    @ConditionalOnProperty(prefix = "jethro.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
    CrossModeAutoRestore crossModeAutoRestore(
            ObjectProvider<UniversePromotionRepository> repo,
            ObjectProvider<UniversePromotionService> service,
            @Value("${jethro.universe.auto-restore-cross-mode:true}") boolean enabled,
            @Value("${jethro.universe.auto-restore-min-batch:5}") int minBatch) {
        return new CrossModeAutoRestore(repo.getIfAvailable(), service.getIfAvailable(), enabled, minBatch);
    }

    @Bean
    @ConditionalOnProperty(prefix = "jethro.universe.dynamic", name = "enabled", havingValue = "true")
    UniversePromotionLifecycle universePromotionLifecycle(UniverseCandidates candidates,
                                                          UniversePromotionEvaluator evaluator,
                                                          InstrumentRefSource refs, CompanyDirectory companies,
                                                          DynamicUniverseProperties props,
                                                          ObjectProvider<UniversePromotionRepository> audit,
                                                          ObjectProvider<UniversePromotionService> service) {
        return new UniversePromotionLifecycle(candidates, evaluator, refs, companies, props, audit, service);
    }
}
