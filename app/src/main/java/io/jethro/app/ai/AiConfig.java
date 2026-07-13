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

    /** Operational telemetry for local-SLM load (latency/tokens per call) — the ops page. */
    @Bean
    InferenceMonitor inferenceMonitor() {
        return new InferenceMonitor();
    }

    @Bean
    @ConditionalOnProperty(prefix = "jethro.ai", name = "enabled", havingValue = "true", matchIfMissing = true)
    ModelInferenceClient modelInferenceClient(AiProperties properties, InferenceMonitor monitor) {
        // Wrap the real Ollama client so every call (commentary, hypotheses, chat) is recorded
        // for the ops view in one place, with no change to any caller.
        var ollama = new OllamaClient(properties.baseUrl(), properties.model(),
                Duration.ofSeconds(properties.requestTimeoutSeconds()));
        return new MonitoringInferenceClient(ollama, monitor);
    }

    /** Loads the model at startup so the first real inference isn't a slow cold-start failure. */
    @Bean
    @ConditionalOnProperty(prefix = "jethro.ai", name = "enabled", havingValue = "true", matchIfMissing = true)
    OllamaWarmup ollamaWarmup(ModelInferenceClient client) {
        return new OllamaWarmup(client);
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
