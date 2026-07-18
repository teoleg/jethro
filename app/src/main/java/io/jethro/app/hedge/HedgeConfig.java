package io.jethro.app.hedge;

import io.jethro.app.risk.TradingHaltSwitch;
import io.jethro.app.risk.VarService;
import io.jethro.order.LastPriceCache;
import io.jethro.order.OrderService;
import io.jethro.trading.riskpnl.InstrumentRefSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

/** Hedge advisor wiring (ADR-0038/0039). All dials are config; the advisor is firm-level v1. */
@Configuration
public class HedgeConfig {

    @Bean
    HedgeAdvisor hedgeAdvisor(
            @Value("${jethro.hedge.mode:ADVISE}") String mode,
            @Value("${jethro.hedge.equity-rebalance-floor-usd:0}") BigDecimal rebalanceFloorUsd,
            @Value("${jethro.hedge.effectiveness-floor:0.25}") double effectivenessFloor,
            @Value("${jethro.hedge.equity-proxy:ES}") String equityProxy,
            @Value("${jethro.hedge.equity-proxy-multiplier:50}") BigDecimal equityProxyMultiplier) {
        return new HedgeAdvisor(HedgeAdvisor.Mode.valueOf(mode.trim().toUpperCase(java.util.Locale.ROOT)),
                rebalanceFloorUsd, effectivenessFloor, equityProxy, equityProxyMultiplier);
    }

    /** AUTO-hedge executor (ADR-0039): submits the sized hedge in AUTO mode, sim-gated. */
    @Bean(destroyMethod = "stop")
    HedgeLifecycle hedgeLifecycle(HedgeAdvisor advisor, ObjectProvider<VarService> varService,
                                  ObjectProvider<InstrumentRefSource> refs, ObjectProvider<LastPriceCache> prices,
                                  ObjectProvider<OrderService> orderService, ObjectProvider<TradingHaltSwitch> haltSwitch,
                                  @Value("${jethro.hedge.book:MACRO}") String hedgeBook,
                                  @Value("${jethro.hedge.cooldown-seconds:60}") long cooldownSeconds,
                                  @Value("${jethro.hedge.interval-seconds:5}") long intervalSeconds) {
        var lifecycle = new HedgeLifecycle(advisor, varService, refs, prices, orderService, haltSwitch,
                hedgeBook, cooldownSeconds, intervalSeconds);
        lifecycle.start();
        return lifecycle;
    }
}

