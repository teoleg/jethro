package io.jethro.app.kafka;

import io.jethro.app.trading.TradingCoreLifecycle;
import io.jethro.uigateway.AttentionFeed;
import io.jethro.uigateway.AttentionRules;
import io.jethro.uigateway.MarkState;
import io.jethro.uigateway.SseBroadcaster;
import io.jethro.uigateway.UiController;
import io.jethro.uigateway.UiGatewayRuntime;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Broker + ui-gateway assembly wiring (ADR-0015: modules are wired here, nowhere else). */
@Configuration
@EnableConfigurationProperties(KafkaConfig.JethroKafkaProperties.class)
public class KafkaConfig {

    @ConfigurationProperties(prefix = "jethro.kafka")
    public record JethroKafkaProperties(boolean enabled, String bootstrapServers) {
    }

    @Configuration
    @ConditionalOnProperty(prefix = "jethro.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class BrokerWiring {

        @Bean(destroyMethod = "close")
        KafkaEventPublisher kafkaEventPublisher(JethroKafkaProperties properties) {
            return new KafkaEventPublisher(properties.bootstrapServers());
        }

        @Bean
        MarkPublisher markPublisher(KafkaEventPublisher publisher, TradingCoreLifecycle tradingCore) {
            return new MarkPublisher(publisher, tradingCore);
        }

        @Bean(destroyMethod = "close")
        UiGatewayRuntime uiGatewayRuntime(JethroKafkaProperties properties, MarkState markState,
                                          AttentionFeed feed, AttentionRules rules, SseBroadcaster sse) {
            var runtime = new UiGatewayRuntime(properties.bootstrapServers(), markState, feed, rules, sse);
            runtime.start();
            return runtime;
        }
    }

    @Bean
    MarkState markState() {
        return new MarkState();
    }

    @Bean
    AttentionFeed attentionFeed() {
        return new AttentionFeed();
    }

    @Bean
    AttentionRules attentionRules(AttentionFeed feed,
                                  @org.springframework.beans.factory.annotation.Value("${jethro.ui.stale-after-millis:10000}") long staleAfterMillis) {
        return new AttentionRules(feed, staleAfterMillis);
    }

    @Bean
    SseBroadcaster sseBroadcaster() {
        return new SseBroadcaster();
    }

    @Bean
    UiController uiController(MarkState markState, AttentionFeed feed, SseBroadcaster sse) {
        return new UiController(markState, feed, sse);
    }
}
