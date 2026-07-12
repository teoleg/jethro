package io.jethro.app.ai;

import io.jethro.app.kafka.KafkaEventPublisher;
import io.jethro.app.trading.TradingCoreLifecycle;
import io.jethro.messaging.Topics;
import io.jethro.trading.algo.agent.DecisionSink;
import io.jethro.trading.algo.agent.RiskCommentator;
import io.jethro.trading.algo.inference.ModelInferenceClient;
import io.jethro.trading.algo.inference.OllamaClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfig {

    @Bean
    BufferingDecisionSink decisionSink() {
        return new BufferingDecisionSink();
    }

    @Bean
    @ConditionalOnProperty(prefix = "jethro.ai", name = "enabled", havingValue = "true", matchIfMissing = true)
    ModelInferenceClient modelInferenceClient(AiProperties properties) {
        return new OllamaClient(properties.baseUrl(), properties.model(),
                Duration.ofSeconds(properties.requestTimeoutSeconds()));
    }

    @Bean
    @ConditionalOnProperty(prefix = "jethro.ai", name = "enabled", havingValue = "true", matchIfMissing = true)
    RiskCommentatorLifecycle riskCommentatorLifecycle(ModelInferenceClient client,
                                                      BufferingDecisionSink buffer,
                                                      ObjectProvider<KafkaEventPublisher> kafka,
                                                      AiProperties properties,
                                                      TradingCoreLifecycle tradingCore) {
        // Composite sink: in-memory buffer (UI cache) + ai.decisions topic when the
        // broker wiring is enabled — invariant 7's audit trail on the log.
        DecisionSink sink = decision -> {
            buffer.record(decision);
            var publisher = kafka.getIfAvailable();
            if (publisher != null) {
                publisher.publish(Topics.AI_DECISIONS, decision.getDecisionId(), decision);
            }
        };
        var commentator = new RiskCommentator(client, sink, properties.maxOutputTokens());
        return new RiskCommentatorLifecycle(commentator, tradingCore, properties.intervalSeconds());
    }
}
