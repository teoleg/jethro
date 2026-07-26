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

    // Live detectors read tuning through StrategyControl (ADR-0052): the SignalParams overrides
    // threshold/floor/lookback/volume-confirm at runtime. The backtest builds its OWN detectors from
    // static config (never this bean), so OOS edge measurement stays reproducible.
    private static io.jethro.trading.algo.strategy.Strategy momentum(StrategyProperties props, StrategyControl live) {
        return new MomentumStrategy(props.lookback(), props.thresholdSigmasOrDefault(),
                props.minSignalBpsOrDefault(), props.volumeConfirmMinOrDefault(), live);
    }

    private static io.jethro.trading.algo.strategy.Strategy meanReversion(StrategyProperties props, StrategyControl live) {
        return new io.jethro.trading.algo.strategy.MeanReversionStrategy(props.lookback(),
                props.thresholdSigmasOrDefault(), props.minSignalBpsOrDefault(), props.volumeConfirmMinOrDefault(), live);
    }

    /**
     * Live strategy-tuning control (ADR-0052): resolves each dial as override-or-config and persists
     * overrides to Postgres when available (else in-memory for the process). Present even when the
     * selector isn't, so the live detector always has a tuning source.
     */
    @Bean
    StrategyControl strategyControl(StrategyProperties props,
                                    ObjectProvider<org.springframework.jdbc.core.JdbcTemplate> jdbc) {
        var template = jdbc.getIfAvailable();
        StrategyOverrideStore store = template != null
                ? new JdbcStrategyOverrideStore(template) : StrategyOverrideStore.NONE;
        return new StrategyControl(props, store);
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
    io.jethro.trading.algo.strategy.Strategy tradingStrategy(
            StrategyProperties props,
            StrategyControl control,
            ObjectProvider<StrategySelector> selector,
            InstrumentRefSource refs,
            @org.springframework.beans.factory.annotation.Value("${jethro.strategy.trend.enabled:true}") boolean trendEnabled,
            @org.springframework.beans.factory.annotation.Value("${jethro.strategy.trend.window:20}") int trendWindow,
            @org.springframework.beans.factory.annotation.Value("${jethro.strategy.trend.upper-band:0.5}") String trendUpper,
            @org.springframework.beans.factory.annotation.Value("${jethro.strategy.trend.lower-band:0.3}") String trendLower,
            @org.springframework.beans.factory.annotation.Value("${jethro.strategy.factor-trend.enabled:false}") boolean factorTrendEnabled,
            @org.springframework.beans.factory.annotation.Value("${jethro.strategy.factor-trend.trend-window:20}") int ftTrendWindow,
            @org.springframework.beans.factory.annotation.Value("${jethro.strategy.factor-trend.vol-window:30}") int ftVolWindow,
            @org.springframework.beans.factory.annotation.Value("${jethro.strategy.factor-trend.trend-threshold-sigmas:1.0}") double ftThreshold,
            @org.springframework.beans.factory.annotation.Value("${jethro.strategy.factor-trend.vol-upper:1.5}") String ftVolUpper,
            @org.springframework.beans.factory.annotation.Value("${jethro.strategy.factor-trend.vol-lower:1.1}") String ftVolLower,
            @org.springframework.beans.factory.annotation.Value("${jethro.strategy.factor-trend.ewma-lambda:0.97}") double ftLambda) {
        // ADR-0070: the factor-level, vol-gated trend follower. When enabled it becomes THE live
        // strategy (it takes a single directional stance from the shared equity factor — the level where
        // this market's edge actually lives). Default OFF: a new strategy must be OOS-validated and turned
        // on deliberately, never silently replace the per-name selector. Its basket is the refdata EQUITY
        // universe (invariant 9: refdata is the universe, no sim list).
        if (factorTrendEnabled) {
            java.util.Set<String> equityBasket = new java.util.LinkedHashSet<>();
            for (String id : refs.instrumentIds()) {
                refs.find(id).filter(r -> "EQUITY".equals(r.assetClass())).ifPresent(r -> equityBasket.add(id));
            }
            return new io.jethro.trading.algo.strategy.FactorTrendStrategy(
                    equityBasket, ftTrendWindow, ftVolWindow, ftThreshold,
                    new java.math.BigDecimal(ftVolUpper), new java.math.BigDecimal(ftVolLower), ftLambda);
        }
        StrategySelector sel = selector.getIfAvailable();
        if (sel == null) {
            return "mean-reversion".equals(props.algoOrDefault()) ? meanReversion(props, control) : momentum(props, control);
        }
        var byAlgo = java.util.Map.of("momentum", momentum(props, control), "mean-reversion", meanReversion(props, control));
        if (trendEnabled) {
            // ADR-0044: a price-derived detector picks momentum (trend) vs mean-reversion (chop) and
            // switches when the regime turns; the OOS selector becomes the edge gate (veto only).
            var detector = new io.jethro.trading.algo.strategy.TrendDetector(
                    trendWindow, new java.math.BigDecimal(trendUpper), new java.math.BigDecimal(trendLower));
            return new io.jethro.trading.algo.strategy.SelectingStrategy(
                    byAlgo, detector, "momentum", "mean-reversion", props.algoOrDefault(), sel::gate);
        }
        // ADR-0043 only: regime-blind OOS pick per instrument.
        return new io.jethro.trading.algo.strategy.SelectingStrategy(byAlgo, sel::algoFor, props.algoOrDefault());
    }

    @Bean
    StrategyLifecycle strategyLifecycle(io.jethro.trading.algo.strategy.Strategy strategy, TradingCoreLifecycle tradingCore,
                                        InstrumentRefSource refs, PreTradeGuardrail guardrail,
                                        RiskProjection risk, RiskLimitSource limits,
                                        AttentionFeed feed, SseBroadcaster sse, StrategyProperties props,
                                        StrategyControl control,
                                        ObjectProvider<OrderService> orderService,
                                        io.jethro.app.risk.TradingHaltSwitch tradingHaltSwitch,
                                        ObjectProvider<io.jethro.app.risk.InstrumentVolSource> vols,
                                        ObjectProvider<io.jethro.app.risk.PortfolioCorrelationSource> correlations,
                                        ObjectProvider<io.jethro.app.order.MeasuredAdvSource> measuredAdv,
                                        ObjectProvider<io.jethro.app.signal.SignalTelemetry> signalTelemetry,
                                        ObjectProvider<io.jethro.app.fusion.ForecastRegistry> forecastRegistry,
                                        @org.springframework.beans.factory.annotation.Value("${jethro.fusion.route-orders:false}") boolean fusionRouting,
                                        @org.springframework.beans.factory.annotation.Value("${jethro.strategy.volregime.window:30}") int volWindow,
                                        @org.springframework.beans.factory.annotation.Value("${jethro.strategy.volregime.upper-factor:1.5}") String volUpper,
                                        @org.springframework.beans.factory.annotation.Value("${jethro.strategy.volregime.lower-factor:1.1}") String volLower,
                                        @org.springframework.beans.factory.annotation.Value("${jethro.strategy.volregime.ewma-lambda:0.97}") String volLambda) {
        // OrderService present only when persistence is on; without it the strategy is
        // suggestion-only even if auto-execute is set. Measured vol likewise — fixed-notional
        // sizing until the daily history accrues. MeasuredAdvSource (ADR-0033) is the live ADV the
        // liquidity cap sizes against; absent → no liquidity cap (falls back to vol/notional caps).
        // ADR-0051: the risk-off scale is driven by a PRICE-DERIVED volatility regime, not the sim label.
        var volRegime = new io.jethro.trading.algo.strategy.VolatilityRegime(
                volWindow, new java.math.BigDecimal(volUpper), new java.math.BigDecimal(volLower),
                new java.math.BigDecimal(volLambda));
        var lifecycle = new StrategyLifecycle(strategy, tradingCore, refs, guardrail, risk, limits, feed, sse, props,
                control, orderService.getIfAvailable(), tradingHaltSwitch,
                vols.getIfAvailable(() -> io.jethro.app.risk.InstrumentVolSource.NONE),
                correlations.getIfAvailable(() -> io.jethro.app.risk.PortfolioCorrelationSource.NONE),
                measuredAdv.getIfAvailable(), volRegime);
        lifecycle.setSignalTelemetry(signalTelemetry.getIfAvailable()); // ADR-0055 phase 1: optional observer
        lifecycle.setForecastRegistry(forecastRegistry.getIfAvailable()); // ADR-0055 phase 4: optional observer
        lifecycle.setFusionRoutingActive(fusionRouting); // ADR-0055 phase 5: stand down when fusion is sole origin
        return lifecycle;
    }
}
