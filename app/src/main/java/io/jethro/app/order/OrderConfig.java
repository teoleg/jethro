package io.jethro.app.order;

import io.jethro.app.kafka.KafkaConfig;
import io.jethro.app.kafka.KafkaEventPublisher;
import io.jethro.domain.Fill;
import io.jethro.domain.Order;
import io.jethro.order.ExecutionCostSource;
import io.jethro.order.LastPriceCache;
import io.jethro.order.OrderController;
import io.jethro.order.OrderEventPublisher;
import io.jethro.order.OrderMarketDataConsumer;
import io.jethro.order.OrderRepository;
import io.jethro.order.OrderService;
import io.jethro.order.PreTradeCheck;
import io.jethro.order.SimulatedExecutor;
import io.jethro.trading.riskpnl.InstrumentRefSource;
import io.jethro.trading.riskpnl.PreTradeGuardrail;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

/**
 * Order-module wiring (ADR-0015). DB-backed, so gated on jethro.persistence.enabled.
 * The broker-facing pieces (event publisher, md.marks consumer) are present only when
 * jethro.kafka.enabled — without the broker, orders still persist but emit no events
 * and simulated fills have no live prices.
 */
@Configuration
@EnableConfigurationProperties(ExecutionProperties.class)
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

    /** Binds execution costs (ADR-0025) to reference data: instrument → asset class →
     *  configured spread/fee. Swaps are rate-quoted (additive spread in rate bp). Without
     *  refdata an instrument gets the conservative EQUITY defaults — never free execution. */
    @Bean
    ExecutionCostSource executionCostSource(ExecutionProperties props,
                                            ObjectProvider<InstrumentRefSource> refSource) {
        InstrumentRefSource refs = refSource.getIfAvailable();
        return instrumentId -> {
            String assetClass = refs != null
                    ? refs.find(instrumentId).map(r -> r.assetClass()).orElse(null)
                    : null;
            BigDecimal spread = props.spreadFor(assetClass);
            BigDecimal fee = props.feeFor(assetClass);
            return new ExecutionCostSource.Cost(spread, fee, "SWAP".equals(assetClass));
        };
    }

    @Bean
    SimulatedExecutor simulatedExecutor(ExecutionCostSource costs) {
        return new SimulatedExecutor(costs);
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

    /** Binds the pre-trade gate to the risk guardrail; approves all if risk isn't wired. */
    @Bean
    PreTradeCheck preTradeCheck(ObjectProvider<PreTradeGuardrail> guardrail) {
        PreTradeGuardrail g = guardrail.getIfAvailable();
        return g != null ? new RiskPreTradeCheck(g) : PreTradeCheck.APPROVE_ALL;
    }

    @Bean
    OrderService orderService(OrderRepository repository, SimulatedExecutor executor,
                              LastPriceCache prices, OrderEventPublisher publisher, PreTradeCheck preTradeCheck) {
        return new OrderService(repository, executor, prices, publisher, preTradeCheck);
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
