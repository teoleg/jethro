package io.jethro.app.risk;

import io.jethro.app.kafka.KafkaConfig;
import io.jethro.app.kafka.KafkaEventPublisher;
import io.jethro.refdata.RefDataRepository;
import io.jethro.trading.riskpnl.InstrumentRefSource;
import io.jethro.trading.riskpnl.RiskProjection;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

/**
 * Risk-pnl assembly wiring (ADR-0015). The projection and REST surface are always present
 * so the UI has an endpoint even before a fill exists; the broker-facing pieces (fills/
 * marks consumer, risk.snapshots publisher) exist only when jethro.kafka.enabled. The
 * instrument reference source binds to reference-data when persistence is on, else an
 * empty source (risk-pnl falls back to multiplier 1).
 */
@Configuration
public class RiskConfig {

    @Bean
    InstrumentRefSource instrumentRefSource(ObjectProvider<RefDataRepository> refData) {
        RefDataRepository repository = refData.getIfAvailable();
        if (repository != null) {
            return new RefDataInstrumentRefSource(repository);
        }
        return instrumentId -> Optional.empty();
    }

    @Bean
    RiskProjection riskProjection(InstrumentRefSource refs) {
        return new RiskProjection(refs);
    }

    @Bean
    RiskController riskController(RiskProjection projection) {
        return new RiskController(projection);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "jethro.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
    RiskDataConsumer riskDataConsumer(KafkaConfig.JethroKafkaProperties properties, RiskProjection projection) {
        var consumer = new RiskDataConsumer(properties.bootstrapServers(), projection);
        consumer.start();
        return consumer;
    }

    @Bean
    @ConditionalOnProperty(prefix = "jethro.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
    RiskSnapshotPublisher riskSnapshotPublisher(RiskProjection projection, KafkaEventPublisher publisher) {
        return new RiskSnapshotPublisher(projection, publisher);
    }
}
