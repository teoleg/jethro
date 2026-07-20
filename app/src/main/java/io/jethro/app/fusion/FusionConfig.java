package io.jethro.app.fusion;

import io.jethro.app.trading.TradingCoreLifecycle;
import io.jethro.domain.Decimals;
import io.jethro.trading.riskpnl.ConsolidatedRisk;
import io.jethro.trading.riskpnl.PositionRisk;
import io.jethro.trading.riskpnl.RiskProjection;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Wires the ADR-0055 fusion layer (phase 4, SHADOW MODE). The {@link ForecastRegistry} is the single
 * bean sources push their current forecast into; the {@link FusionLifecycle} reads it on a cadence,
 * builds the target book, and surfaces it — placing NO orders. Gated on {@code jethro.fusion.enabled};
 * all sizing dials are placeholders (see application.properties) and inert until live routing is wired.
 */
@Configuration
public class FusionConfig {

    @Bean
    @ConditionalOnProperty(prefix = "jethro.fusion", name = "enabled", havingValue = "true", matchIfMissing = true)
    ForecastRegistry forecastRegistry(
            @Value("${jethro.fusion.strategy.expected-abs-z:3.0}") double expectedAbsZ,
            @Value("${jethro.fusion.hypothesis.conviction-step:5.0}") double convictionStep,
            @Value("${jethro.fusion.social.per-channel:4.0}") double socialPerChannel,
            @Value("${jethro.fusion.learned.scale:20.0}") double learnedScale,
            @Value("${jethro.fusion.freshness-seconds:600}") long freshnessSeconds) {
        var params = new ForecastRegistry.Params(expectedAbsZ, convictionStep, socialPerChannel, learnedScale);
        return new ForecastRegistry(params, freshnessSeconds * 1_000);
    }

    /** The sole-origin order path (ADR-0055 §5), present only when the order module is wired
     *  (persistence on). Absent → the fusion loop can only run in shadow. */
    @Bean
    @ConditionalOnProperty(prefix = "jethro.fusion", name = "enabled", havingValue = "true", matchIfMissing = true)
    FusionExecutor fusionExecutor(io.jethro.app.strategy.StrategyProperties props,
                                  io.jethro.trading.riskpnl.InstrumentRefSource refs,
                                  io.jethro.trading.riskpnl.PreTradeGuardrail guardrail,
                                  io.jethro.app.risk.TradingHaltSwitch halt,
                                  ObjectProvider<io.jethro.order.OrderService> orderService,
                                  ObjectProvider<io.jethro.app.strategy.StrategySelector> selector) {
        io.jethro.order.OrderService os = orderService.getIfAvailable();
        if (os == null) {
            return null; // no order path — the lifecycle falls back to shadow
        }
        return new FusionExecutor(props, refs, guardrail, halt, os, selector);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "jethro.fusion", name = "enabled", havingValue = "true", matchIfMissing = true)
    FusionLifecycle fusionLifecycle(ForecastRegistry registry,
                                    ObjectProvider<TradingCoreLifecycle> tradingCore,
                                    ObjectProvider<RiskProjection> risk,
                                    ObjectProvider<FusionExecutor> executor,
                                    @Value("${jethro.fusion.assumed-correlation:0.5}") double assumedCorrelation,
                                    @Value("${jethro.fusion.unit-notional-usd:10000}") BigDecimal unitNotional,
                                    @Value("${jethro.fusion.buffer-fraction:0.2}") double bufferFraction,
                                    @Value("${jethro.fusion.adjustment-rate:0.5}") double adjustmentRate,
                                    @Value("${jethro.fusion.route-orders:false}") boolean routeOrders,
                                    @Value("${jethro.fusion.interval-seconds:30}") long intervalSeconds) {
        var params = new FusionPlanner.Params(assumedCorrelation, unitNotional, bufferFraction, adjustmentRate);
        var lifecycle = new FusionLifecycle(registry,
                instrument -> priceFor(tradingCore, instrument),
                () -> firmPositions(risk),
                FusionWeights.equal(), params, routeOrders, executor.getIfAvailable(), intervalSeconds);
        lifecycle.start();
        return lifecycle;
    }

    @Bean
    @ConditionalOnProperty(prefix = "jethro.fusion", name = "enabled", havingValue = "true", matchIfMissing = true)
    FusionController fusionController(ObjectProvider<FusionLifecycle> fusion) {
        return new FusionController(fusion);
    }

    /** Firm-wide net quantity per instrument, summed across books from the risk snapshot. */
    private static Map<String, BigDecimal> firmPositions(ObjectProvider<RiskProjection> risk) {
        RiskProjection projection = risk.getIfAvailable();
        Map<String, BigDecimal> out = new HashMap<>();
        if (projection == null) {
            return out;
        }
        ConsolidatedRisk snap = projection.snapshot(System.currentTimeMillis());
        for (PositionRisk p : snap.positions()) {
            out.merge(p.instrumentId(), p.quantity(), BigDecimal::add);
        }
        return out;
    }

    private static BigDecimal priceFor(ObjectProvider<TradingCoreLifecycle> tradingCore, String instrument) {
        TradingCoreLifecycle core = tradingCore.getIfAvailable();
        if (core == null || core.runtime() == null) {
            return null;
        }
        var holder = core.runtime().markCache().get(instrument);
        if (holder == null || holder.priceScaled() <= 0) {
            return null;
        }
        return Decimals.fromScaledLong(holder.priceScaled(), Decimals.PRICE_SCALE);
    }
}
