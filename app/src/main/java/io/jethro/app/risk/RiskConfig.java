package io.jethro.app.risk;

import io.jethro.app.kafka.KafkaConfig;
import io.jethro.app.kafka.KafkaEventPublisher;
import io.jethro.refdata.RefDataRepository;
import io.jethro.trading.riskpnl.CurveService;
import io.jethro.trading.riskpnl.InstrumentRefSource;
import io.jethro.trading.riskpnl.PreTradeGuardrail;
import io.jethro.trading.riskpnl.RiskLimitEvaluator;
import io.jethro.trading.riskpnl.RiskLimitSource;
import io.jethro.trading.riskpnl.RiskProjection;
import io.jethro.uigateway.AttentionFeed;
import io.jethro.uigateway.SseBroadcaster;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
@EnableConfigurationProperties(RiskLimitProperties.class)
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

    /** Live SOFR curve from streamed tenor quotes (quant-engine phase 4). */
    @Bean
    CurveService curveService() {
        return new CurveService();
    }

    /** Strata swap valuation (PV/DV01/par) on the live curve. */
    @Bean
    io.jethro.trading.riskpnl.SwapPricingService swapPricingService(CurveService curveService) {
        return new io.jethro.trading.riskpnl.SwapPricingService(curveService);
    }

    /** Deterministic scenario/stress over live positions (quant-engine step 2). Swap scenarios
     *  are full revaluation on the shocked curve (convexity) via the Strata pricer. */
    @Bean
    io.jethro.trading.riskpnl.ScenarioEngine scenarioEngine(InstrumentRefSource refs,
                                                            io.jethro.trading.riskpnl.SwapPricingService swapPricing) {
        return new io.jethro.trading.riskpnl.ScenarioEngine(refs, swapPricing);
    }

    @Bean
    RiskController riskController(RiskProjection projection, CurveService curveService,
                                  io.jethro.trading.riskpnl.SwapPricingService swapPricing,
                                  io.jethro.trading.riskpnl.ScenarioEngine scenarioEngine) {
        return new RiskController(projection, curveService, swapPricing, scenarioEngine);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "jethro.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
    RiskDataConsumer riskDataConsumer(KafkaConfig.JethroKafkaProperties properties, RiskProjection projection,
                                      CurveService curveService) {
        var consumer = new RiskDataConsumer(properties.bootstrapServers(), projection, curveService);
        consumer.start();
        return consumer;
    }

    @Bean
    @ConditionalOnProperty(prefix = "jethro.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
    RiskSnapshotPublisher riskSnapshotPublisher(RiskProjection projection, KafkaEventPublisher publisher) {
        return new RiskSnapshotPublisher(projection, publisher);
    }

    @Bean
    RiskLimitSource riskLimitSource(RiskLimitProperties properties) {
        return new ConfiguredRiskLimitSource(properties);
    }

    @Bean
    RiskLimitEvaluator riskLimitEvaluator(RiskLimitProperties properties) {
        return new RiskLimitEvaluator(properties.warnRatioOrDefault());
    }

    /** Pre-trade exposure guardrail (ADR-0018), consumed by the order module via its port. */
    @Bean
    PreTradeGuardrail preTradeGuardrail(RiskProjection projection, RiskLimitSource limits) {
        return new PreTradeGuardrail(projection, limits);
    }

    /** Risk-limit breaches feed the attention floor; needs live fills, so gated on the broker. */
    @Bean
    @ConditionalOnProperty(prefix = "jethro.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
    RiskLimitMonitor riskLimitMonitor(RiskProjection projection, RiskLimitEvaluator evaluator,
                                      RiskLimitSource limits, AttentionFeed feed, SseBroadcaster sse) {
        return new RiskLimitMonitor(projection, evaluator, limits, feed, sse);
    }

    /** Worst-stress-vs-loss-cap attention trigger (deterministic floor, ADR-0017). */
    @Bean
    @ConditionalOnProperty(prefix = "jethro.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
    ScenarioMonitor scenarioMonitor(RiskProjection projection,
                                    io.jethro.trading.riskpnl.ScenarioEngine scenarioEngine,
                                    RiskLimitProperties limits, AttentionFeed feed, SseBroadcaster sse) {
        return new ScenarioMonitor(projection, scenarioEngine, limits, feed, sse);
    }
}
