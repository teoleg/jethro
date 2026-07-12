package io.jethro.app.order;

import io.jethro.app.kafka.KafkaConfig;
import io.jethro.app.kafka.KafkaEventPublisher;
import io.jethro.domain.Fill;
import io.jethro.domain.Order;
import io.jethro.order.LastPriceCache;
import io.jethro.order.OrderController;
import io.jethro.order.OrderEventPublisher;
import io.jethro.order.OrderMarketDataConsumer;
import io.jethro.order.OrderRepository;
import io.jethro.order.OrderService;
import io.jethro.order.SimulatedExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Order-module wiring (ADR-0015). DB-backed, so gated on jethro.persistence.enabled.
 * The broker-facing pieces (event publisher, md.marks consumer) are present only when
 * jethro.kafka.enabled — without the broker, orders still persist but emit no events
 * and simulated fills have no live prices.
 */
@Configuration
@ConditionalOnProperty(prefix = "jethro.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OrderConfig {

    @Bean
    OrderRepository orderRepository(JdbcTemplate jdbcTemplate) {
        return new OrderRepository(jdbcTemplate);
    }

    @Bean
    LastPriceCache lastPriceCache() {
        return new LastPriceCache();
    }

    @Bean
    SimulatedExecutor simulatedExecutor() {
        return new SimulatedExecutor();
    }

    @Bean
    OrderEventPublisher orderEventPublisher(ObjectProvider<KafkaEventPublisher> kafka) {
        KafkaEventPublisher publisher = kafka.getIfAvailable();
        if (publisher != null) {
            return new KafkaOrderEventPublisher(publisher);
        }
        return new OrderEventPublisher() { // no broker: persist orders, emit nothing
            @Override
            public void publishOrderEvent(Order order, String reason) {
            }

            @Override
            public void publishFill(Fill fill) {
            }
        };
    }

    @Bean
    OrderService orderService(OrderRepository repository, SimulatedExecutor executor,
                              LastPriceCache prices, OrderEventPublisher publisher) {
        return new OrderService(repository, executor, prices, publisher);
    }

    @Bean
    OrderController orderController(OrderService orderService, OrderRepository repository) {
        return new OrderController(orderService, repository);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "jethro.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
    OrderMarketDataConsumer orderMarketDataConsumer(KafkaConfig.JethroKafkaProperties properties,
                                                    LastPriceCache prices) {
        var consumer = new OrderMarketDataConsumer(properties.bootstrapServers(), prices);
        consumer.start();
        return consumer;
    }
}
