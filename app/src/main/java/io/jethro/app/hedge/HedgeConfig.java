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
            ObjectProvider<InstrumentRefSource> refs,
            @Value("${jethro.hedge.mode:ADVISE}") String mode,
            @Value("${jethro.hedge.equity-rebalance-floor-usd:0}") BigDecimal rebalanceFloorUsd,
            @Value("${jethro.hedge.min-trade-notional-usd:10000}") BigDecimal minTradeNotionalUsd,
            @Value("${jethro.hedge.no-trade-band-fraction:0.25}") BigDecimal noTradeBandFraction,
            @Value("${jethro.hedge.effectiveness-floor:0.25}") double effectivenessFloor,
            @Value("${jethro.hedge.min-covariance-days:40}") int minCovarianceDays,
            @Value("${jethro.hedge.equity-proxy-candidates:ES,NQ}") java.util.List<String> proxyCandidates,
            @Value("${jethro.hedge.proxy-switch-margin:0.10}") double proxySwitchMargin,
            @Value("${jethro.hedge.equity-proxy:ES}") String equityProxy,
            @Value("${jethro.hedge.equity-proxy-multiplier:50}") BigDecimal equityProxyMultiplier,
            @Value("${jethro.hedge.churn-sigma-multiple:1.0}") BigDecimal churnSigmaMultiple) {
        // Contract multiplier per candidate from refdata; the configured proxy keeps its config
        // fallback so the advisor works before refdata loads.
        java.util.function.Function<String, java.util.Optional<BigDecimal>> multiplierOf = id -> {
            InstrumentRefSource rf = refs.getIfAvailable();
            java.util.Optional<BigDecimal> fromRef = rf == null ? java.util.Optional.empty()
                    : rf.find(id).map(io.jethro.trading.riskpnl.InstrumentRef::multiplier);
            return fromRef.isPresent() ? fromRef
                    : id.equals(equityProxy) ? java.util.Optional.of(equityProxyMultiplier)
                    : java.util.Optional.empty();
        };
        return new HedgeAdvisor(HedgeAdvisor.Mode.valueOf(mode.trim().toUpperCase(java.util.Locale.ROOT)),
                rebalanceFloorUsd, minTradeNotionalUsd, noTradeBandFraction,
                effectivenessFloor, minCovarianceDays,
                proxyCandidates, proxySwitchMargin, equityProxy, multiplierOf, churnSigmaMultiple);
    }

    /** ADR-0098: how far the hedge target moves between the moments the hedge can act on it.
     *  Sampled at the hedge COOLDOWN — the soonest one hedge order can be followed by the next —
     *  because that is the horizon a target must survive to be worth trading. */
    @Bean
    HedgeTargetChurn hedgeTargetChurn(
            @Value("${jethro.hedge.cooldown-seconds:60}") long cooldownSeconds) {
        return new HedgeTargetChurn(cooldownSeconds * 1_000);
    }

    /** AUTO-hedge executor (ADR-0039): submits the hedge DELTA in AUTO mode, sim-gated. The hedge
     *  trades in its own dedicated book (default HEDGE) so its position is unambiguous feedback
     *  for the advisor and never collides with the strategy's own futures (which route to MACRO). */
    @Bean(destroyMethod = "stop")
    HedgeLifecycle hedgeLifecycle(HedgeAdvisor advisor, HedgeTargetChurn churn,
                                  ObjectProvider<VarService> varService,
                                  ObjectProvider<InstrumentRefSource> refs, ObjectProvider<LastPriceCache> prices,
                                  ObjectProvider<OrderService> orderService, ObjectProvider<TradingHaltSwitch> haltSwitch,
                                  ObjectProvider<io.jethro.trading.riskpnl.RiskProjection> projection,
                                  ObjectProvider<io.jethro.app.trading.TradingCoreLifecycle> tradingCore,
                                  @Value("${jethro.hedge.book:HEDGE}") String hedgeBook,
                                  @Value("${jethro.hedge.cooldown-seconds:60}") long cooldownSeconds,
                                  @Value("${jethro.hedge.interval-seconds:5}") long intervalSeconds) {
        var lifecycle = new HedgeLifecycle(advisor, churn, varService, refs, prices, orderService,
                haltSwitch, projection, tradingCore, hedgeBook, cooldownSeconds, intervalSeconds);
        lifecycle.start();
        return lifecycle;
    }
}

