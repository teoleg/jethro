package io.jethro.app.strategy;

import io.jethro.app.trading.TradingCoreLifecycle;
import io.jethro.order.OrderService;
import io.jethro.trading.algo.strategy.MomentumStrategy;
import io.jethro.trading.riskpnl.InstrumentRefSource;
import io.jethro.trading.riskpnl.PreTradeGuardrail;
import io.jethro.trading.riskpnl.RiskLimitSource;
import io.jethro.trading.riskpnl.RiskProjection;
import io.jethro.uigateway.AttentionFeed;
import io.jethro.uigateway.SseBroadcaster;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Toy-strategy wiring (step 8 / ADR-0018 deterministic candidate stage). Gated on
 * jethro.strategy.enabled. The strategy proposes suggestions onto the attention feed;
 * the pre-trade guardrail keeps them admissible; a human executes from the ticket.
 */
@Configuration
@EnableConfigurationProperties(StrategyProperties.class)
@ConditionalOnProperty(prefix = "jethro.strategy", name = "enabled", havingValue = "true", matchIfMissing = true)
public class StrategyConfig {

    private static io.jethro.trading.algo.strategy.Strategy momentum(StrategyProperties props) {
        return new MomentumStrategy(props.lookback(), props.thresholdSigmasOrDefault(),
                props.minSignalBpsOrDefault(), props.volumeConfirmMinOrDefault());
    }

    private static io.jethro.trading.algo.strategy.Strategy meanReversion(StrategyProperties props) {
        return new io.jethro.trading.algo.strategy.MeanReversionStrategy(props.lookback(),
                props.thresholdSigmasOrDefault(), props.minSignalBpsOrDefault(), props.volumeConfirmMinOrDefault());
    }

    /**
     * Per-instrument strategy selector (ADR-0043) — measures momentum vs mean-reversion OOS and
     * picks per instrument. Present only when {@code jethro.strategy.selection.enabled=true} (default)
     * AND the backtest service is available. Runs its measurement on a background thread.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "jethro.strategy.selection", name = "enabled", havingValue = "true", matchIfMissing = true)
    StrategySelector strategySelector(
            ObjectProvider<io.jethro.app.backtest.BacktestService> backtest,
            @org.springframework.beans.factory.annotation.Value("${jethro.strategy.selection.seed-count:9}") int seedCount,
            @org.springframework.beans.factory.annotation.Value("${jethro.strategy.selection.ticks:5000}") int ticks,
            @org.springframework.beans.factory.annotation.Value("${jethro.strategy.selection.interval-minutes:60}") long intervalMinutes,
            @org.springframework.beans.factory.annotation.Value("${jethro.strategy.selection.initial-delay-seconds:90}") long initialDelaySeconds) {
        var svc = backtest.getIfAvailable();
        if (svc == null) {
            return null; // no backtest service (persistence off) — falls back to the single-algo bean
        }
        var selector = new StrategySelector(svc, seedCount, ticks, intervalMinutes, initialDelaySeconds);
        selector.start();
        return selector;
    }

    /**
     * The live signal strategy. With the ADR-0043 selector present, this is a {@link
     * io.jethro.trading.algo.strategy.SelectingStrategy} that routes each instrument to the algo
     * the OOS harness chose (and trades nothing where neither has an edge), falling back to the
     * configured {@code jethro.strategy.algo} until the first measurement lands. Without the
     * selector it is the single configured algo (momentum or mean-reversion).
     */
    @Bean
    io.jethro.trading.algo.strategy.Strategy tradingStrategy(StrategyProperties props,
                                                             ObjectProvider<StrategySelector> selector) {
        StrategySelector sel = selector.getIfAvailable();
        if (sel == null) {
            return "mean-reversion".equals(props.algoOrDefault()) ? meanReversion(props) : momentum(props);
        }
        var byAlgo = java.util.Map.of("momentum", momentum(props), "mean-reversion", meanReversion(props));
        return new io.jethro.trading.algo.strategy.SelectingStrategy(byAlgo, sel::algoFor, props.algoOrDefault());
    }

    @Bean
    StrategyLifecycle strategyLifecycle(io.jethro.trading.algo.strategy.Strategy strategy, TradingCoreLifecycle tradingCore,
                                        InstrumentRefSource refs, PreTradeGuardrail guardrail,
                                        RiskProjection risk, RiskLimitSource limits,
                                        AttentionFeed feed, SseBroadcaster sse, StrategyProperties props,
                                        ObjectProvider<OrderService> orderService,
                                        io.jethro.app.risk.TradingHaltSwitch tradingHaltSwitch,
                                        ObjectProvider<io.jethro.app.risk.InstrumentVolSource> vols,
                                        ObjectProvider<io.jethro.app.risk.PortfolioCorrelationSource> correlations,
                                        ObjectProvider<io.jethro.app.order.MeasuredAdvSource> measuredAdv) {
        // OrderService present only when persistence is on; without it the strategy is
        // suggestion-only even if auto-execute is set. Measured vol likewise — fixed-notional
        // sizing until the daily history accrues. MeasuredAdvSource (ADR-0033) is the live ADV the
        // liquidity cap sizes against; absent → no liquidity cap (falls back to vol/notional caps).
        return new StrategyLifecycle(strategy, tradingCore, refs, guardrail, risk, limits, feed, sse, props,
                orderService.getIfAvailable(), tradingHaltSwitch,
                vols.getIfAvailable(() -> io.jethro.app.risk.InstrumentVolSource.NONE),
                correlations.getIfAvailable(() -> io.jethro.app.risk.PortfolioCorrelationSource.NONE),
                measuredAdv.getIfAvailable());
    }
}
