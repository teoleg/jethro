package io.jethro.app.hypothesis;

import io.jethro.app.ai.BufferingDecisionSink;
import io.jethro.app.kafka.KafkaEventPublisher;
import io.jethro.app.strategy.StrategyProperties;
import io.jethro.app.trading.TradingCoreLifecycle;
import io.jethro.messaging.Topics;
import io.jethro.trading.algo.agent.DecisionSink;
import io.jethro.trading.algo.hypothesis.HypothesisGenerator;
import io.jethro.trading.algo.inference.ModelInferenceClient;
import io.jethro.trading.riskpnl.InstrumentRefSource;
import io.jethro.trading.riskpnl.PreTradeGuardrail;
import io.jethro.trading.riskpnl.RiskProjection;
import io.jethro.uigateway.AttentionFeed;
import io.jethro.uigateway.SseBroadcaster;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM hypothesis layer wiring (ADR-0022). Gated on jethro.hypothesis.enabled. The evaluator
 * and REST surface are always present; the generator lifecycle needs a model, so it's also
 * gated on jethro.ai.enabled — without one, the deterministic pieces stand and the feed just
 * has no hypotheses (the market path never depends on the model, invariant 7).
 */
@Configuration
@EnableConfigurationProperties(HypothesisProperties.class)
@ConditionalOnProperty(prefix = "jethro.hypothesis", name = "enabled", havingValue = "true", matchIfMissing = true)
public class HypothesisConfig {

    @Bean
    HypothesisEvaluator hypothesisEvaluator(InstrumentRefSource refs, PreTradeGuardrail guardrail,
                                            StrategyProperties sizing) {
        return new HypothesisEvaluator(refs, guardrail, sizing);
    }

    @Bean
    SimNarrativeFeed simNarrativeFeed(HypothesisProperties props) {
        return new SimNarrativeFeed(props.narrativeSeedOrDefault());
    }

    @Bean
    @ConditionalOnProperty(prefix = "jethro.ai", name = "enabled", havingValue = "true", matchIfMissing = true)
    HypothesisLifecycle hypothesisLifecycle(ModelInferenceClient client, BufferingDecisionSink buffer,
                                            ObjectProvider<KafkaEventPublisher> kafka, HypothesisProperties props,
                                            HypothesisEvaluator evaluator, SimNarrativeFeed narrativeFeed,
                                            io.jethro.app.backtest.BacktestService backtest,
                                            TradingCoreLifecycle tradingCore, RiskProjection risk,
                                            InstrumentRefSource refs, AttentionFeed feed, SseBroadcaster sse) {
        // Same composite sink as the commentator: in-memory buffer + ai.decisions topic when
        // the broker is wired — every hypothesis-generation run is an audited AiDecision.
        DecisionSink sink = decision -> {
            buffer.record(decision);
            var publisher = kafka.getIfAvailable();
            if (publisher != null) {
                publisher.publish(Topics.AI_DECISIONS, decision.getDecisionId(), decision);
            }
        };
        var generator = new HypothesisGenerator(client, sink,
                props.maxPerCycleOrDefault(), props.maxOutputTokensOrDefault());
        return new HypothesisLifecycle(generator, evaluator, narrativeFeed, backtest,
                tradingCore, risk, refs, feed, sse, props);
    }

    @Bean
    HypothesisController hypothesisController(ObjectProvider<HypothesisLifecycle> lifecycle) {
        return new HypothesisController(lifecycle);
    }
}
