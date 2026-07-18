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

    /** The configured signal algo (jethro.strategy.algo): momentum or mean-reversion — both
     *  drive the same lifecycle/guardrails/harness through the Strategy port. */
    @Bean
    io.jethro.trading.algo.strategy.Strategy tradingStrategy(StrategyProperties props) {
        double volumeConfirm = props.volumeConfirmMinOrDefault(); // ADR-0033: participation gate
        if ("mean-reversion".equals(props.algoOrDefault())) {
            return new io.jethro.trading.algo.strategy.MeanReversionStrategy(
                    props.lookback(), props.thresholdSigmasOrDefault(), props.minSignalBpsOrDefault(), volumeConfirm);
        }
        return new MomentumStrategy(props.lookback(), props.thresholdSigmasOrDefault(),
                props.minSignalBpsOrDefault(), volumeConfirm);
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
