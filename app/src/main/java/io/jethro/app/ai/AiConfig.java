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

    /** The one-at-a-time gate over the single-model box — shared by generate and embed so RAG
     *  retrieval can't collide with an inference (unconditional so RAG can use it even if the
     *  commentary loop is off). */
    @Bean
    OllamaGate ollamaGate() {
        return new OllamaGate();
    }

    @Bean
    @ConditionalOnProperty(prefix = "jethro.ai", name = "enabled", havingValue = "true", matchIfMissing = true)
    ModelInferenceClient modelInferenceClient(AiProperties properties, InferenceMonitor monitor, OllamaGate gate) {
        // Layers (outer → inner): single-flight over the shared gate (one model call at a time,
        // skip if busy — the fast busy-skip is NOT monitored) → monitor (records real calls for the
        // ops view) → circuit breaker (a wedged Ollama trips it and backs off, so we don't hammer a
        // sick model with 120s-hanging calls every cycle) → Ollama.
        var ollama = new OllamaClient(properties.baseUrl(), properties.model(),
                Duration.ofSeconds(properties.requestTimeoutSeconds()));
        var breaker = new CircuitBreakerInferenceClient(ollama, 3, Duration.ofSeconds(60));
        return new SingleFlightInferenceClient(new MonitoringInferenceClient(breaker, monitor), gate);
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
