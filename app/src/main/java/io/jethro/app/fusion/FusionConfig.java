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
                                    @org.springframework.beans.factory.annotation.Qualifier("sharedScheduler") java.util.concurrent.ScheduledExecutorService scheduler,
                                    @Value("${jethro.fusion.assumed-correlation:0.5}") double assumedCorrelation,
                                    @Value("${jethro.fusion.unit-notional-usd:10000}") BigDecimal unitNotional,
                                    @Value("${jethro.fusion.buffer-fraction:0.2}") double bufferFraction,
                                    @Value("${jethro.fusion.adjustment-rate:0.5}") double adjustmentRate,
                                    @Value("${jethro.fusion.route-orders:false}") boolean routeOrders,
                                    @Value("${jethro.fusion.interval-seconds:30}") long intervalSeconds,
                                    @Value("${jethro.fusion.min-forecast-to-route:5.0}") double minForecastToRoute,
                                    ObjectProvider<io.jethro.app.signal.SignalTelemetry> telemetry,
                                    @Value("${jethro.fusion.weights.mode:telemetry}") String weightsMode,
                                    @Value("${jethro.fusion.weights.shrinkage-k:20}") double shrinkageK,
                                    @Value("${jethro.fusion.weights.min:0.25}") double weightMin,
                                    @Value("${jethro.fusion.weights.max:3.0}") double weightMax,
                                    @Value("${jethro.fusion.weights.min-sample:20}") int weightMinSample,
                                    ObjectProvider<io.jethro.order.ExecutionQualityRepository> tca,
                                    @Value("${jethro.fusion.edge-gate.enabled:true}") boolean edgeGateEnabled,
                                    @Value("${jethro.fusion.edge-gate.min-sample:30}") int edgeGateMinSample,
                                    @Value("${jethro.fusion.edge-gate.t-hurdle:2.0}") double edgeGateTHurdle,
                                    @Value("${jethro.hedge.book:HEDGE}") String hedgeBook) {
        var params = new FusionPlanner.Params(assumedCorrelation, unitNotional, bufferFraction, adjustmentRate);
        // ADR-0055 item 6: per-source weights are re-estimated from the phase-1 telemetry each cycle
        // (evidence, not decree), shrunk toward equal so a thin sample can't dominate. mode=equal forces
        // the flat placeholder; telemetry (default) falls back to equal when the store is absent or cold.
        var weightParams = new TelemetryWeights.Params(shrinkageK, weightMin, weightMax, weightMinSample);
        java.util.function.Supplier<FusionWeights> weightsSupplier =
                "equal".equalsIgnoreCase(weightsMode)
                        ? FusionWeights::equal
                        : () -> {
                            var t = telemetry.getIfAvailable();
                            return t == null ? FusionWeights.equal()
                                    : FusionWeights.fromTelemetry(t.stats(), weightParams);
                        };
        // ADR-0064: the edge gate re-reads BOTH measurements every cycle — per-source realised
        // expectancy (signal telemetry) and the desk's own realised slippage (TCA) — so it opens by
        // itself the moment a source earns its cost, and closes again if that decays. Nothing here is
        // a chosen number: the only dials are the significance hurdle and the minimum sample.
        var gateParams = new EdgeGate.Params(edgeGateMinSample, edgeGateTHurdle);
        java.util.function.Supplier<EdgeGate.Decision> gateSupplier = !edgeGateEnabled ? null
                : () -> {
                    var t = telemetry.getIfAvailable();
                    var q = tca.getIfAvailable();
                    if (t == null || q == null) {
                        return null; // no measurement path — leave the pre-existing controls alone
                    }
                    try {
                        // null ⇒ nothing filled in this mode yet; the gate stays open rather than
                        // assume a cost. A failed read must never stop the planning loop.
                        Double roundTripBps = q.averageSlippageBps()
                                .map(oneWay -> oneWay.doubleValue() * 2.0)
                                .orElse(null);
                        return EdgeGate.evaluate(t.stats(), roundTripBps, gateParams);
                    } catch (RuntimeException e) {
                        return null;
                    }
                };
        var lifecycle = new FusionLifecycle(registry,
                instrument -> priceFor(tradingCore, instrument),
                () -> firmPositions(risk),
                () -> heldInRoutedBooks(risk, hedgeBook),
                weightsSupplier, params, routeOrders, executor.getIfAvailable(), scheduler, intervalSeconds,
                minForecastToRoute, gateSupplier);
        lifecycle.start();
        return lifecycle;
    }

    /**
     * The ADR-0066 trend sensor. A forecast source only: it publishes a continuous, self-normalised
     * EWMAC reading per name into the same registry every other source pushes to, and records its calls
     * in the phase-1 telemetry so its edge is measured like anyone else's. It cannot place an order and
     * cannot relax a gate — the edge gate, conviction floor, backtest-support veto and the deterministic
     * floor all still stand between a forecast and a fill.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "jethro.fusion.trend", name = "enabled", havingValue = "true", matchIfMissing = true)
    TrendForecastLifecycle trendForecastLifecycle(
            ForecastRegistry registry,
            ObjectProvider<TradingCoreLifecycle> tradingCore,
            ObjectProvider<io.jethro.app.signal.SignalTelemetry> telemetry,
            @org.springframework.beans.factory.annotation.Qualifier("sharedScheduler") java.util.concurrent.ScheduledExecutorService scheduler,
            @Value("${jethro.fusion.trend.fast-span:16}") int fastSpan,
            @Value("${jethro.fusion.trend.slow-span:64}") int slowSpan,
            @Value("${jethro.fusion.trend.normalisation-span:256}") int normalisationSpan,
            ObjectProvider<io.jethro.uigateway.MarkHistory> markHistory,
            @Value("${jethro.fusion.trend.interval-seconds:5}") long intervalSeconds) {
        var forecaster = new io.jethro.trading.algo.strategy.EwmacTrendForecaster(
                new io.jethro.trading.algo.strategy.EwmacTrendForecaster.Params(fastSpan, slowSpan, normalisationSpan));
        var lifecycle = new TrendForecastLifecycle(forecaster, registry, tradingCore.getIfAvailable(),
                telemetry.getIfAvailable(), storedPrices(markHistory), scheduler, intervalSeconds);
        lifecycle.start();
        return lifecycle;
    }

    /**
     * The ADR-0070 mean-reversion sensor — the chop-regime counterpart of the trend sensor above, and
     * under exactly the same contract. It publishes a continuous, self-normalised range-position reading
     * per name into the same registry and records its calls in the phase-1 telemetry, so it must earn a
     * measured expectancy before the edge gate lets it put risk on. It cannot place an order and cannot
     * relax a gate; while the gate is reduce-only it can only change how a held position is worked down.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "jethro.fusion.reversion", name = "enabled", havingValue = "true", matchIfMissing = true)
    ReversionForecastLifecycle reversionForecastLifecycle(
            ForecastRegistry registry,
            ObjectProvider<TradingCoreLifecycle> tradingCore,
            ObjectProvider<io.jethro.app.signal.SignalTelemetry> telemetry,
            @org.springframework.beans.factory.annotation.Qualifier("sharedScheduler") java.util.concurrent.ScheduledExecutorService scheduler,
            @Value("${jethro.fusion.reversion.range-span:120}") int rangeSpan,
            @Value("${jethro.fusion.reversion.normalisation-span:240}") int normalisationSpan,
            ObjectProvider<io.jethro.uigateway.MarkHistory> markHistory,
            @Value("${jethro.fusion.reversion.interval-seconds:10}") long intervalSeconds) {
        var forecaster = new io.jethro.trading.algo.strategy.RangeReversionForecaster(
                new io.jethro.trading.algo.strategy.RangeReversionForecaster.Params(rangeSpan, normalisationSpan));
        var lifecycle = new ReversionForecastLifecycle(forecaster, registry, tradingCore.getIfAvailable(),
                telemetry.getIfAvailable(), storedPrices(markHistory), scheduler, intervalSeconds);
        lifecycle.start();
        return lifecycle;
    }

    /**
     * Adapts the durable chart price history to the sensors' warm-restart seed (ADR-0071). It is the
     * same {@code md.marks} series the sensors consume live, already persisted and already surviving a
     * restart (ADR-0014 derived data) — so warming from it replays the stream the sensor would have
     * seen, not a different one. Absent (in-memory profile, tests) → the sensors cold-start as before.
     */
    private static SensorWarmup.History storedPrices(ObjectProvider<io.jethro.uigateway.MarkHistory> provider) {
        io.jethro.uigateway.MarkHistory history = provider.getIfAvailable();
        if (history == null) {
            return null;
        }
        return (instrumentId, sinceMillis) -> {
            var out = new java.util.ArrayList<SensorWarmup.Point>();
            for (var point : history.since(instrumentId, sinceMillis)) {
                try {
                    out.add(new SensorWarmup.Point(point.t(), new BigDecimal(point.price())));
                } catch (NumberFormatException | NullPointerException e) {
                    // a single unparseable stored price must not cost the whole seed
                }
            }
            return out;
        };
    }

    @Bean
    @ConditionalOnProperty(prefix = "jethro.fusion", name = "enabled", havingValue = "true", matchIfMissing = true)
    FusionController fusionController(ObjectProvider<FusionLifecycle> fusion) {
        return new FusionController(fusion);
    }

    /**
     * The names the fusion layer is RESPONSIBLE for (ADR-0065): every instrument it currently holds in
     * a book it routes into. These are planned each cycle even with no fresh forecast, so a position
     * whose sources have gone silent gets an explicit target of flat rather than being orphaned.
     *
     * <p>The hedge book is excluded. Its position is not a view — it is the ADR-0019 hedger's own
     * target, maintained against the strategy books' residual exposure. Fusion routes by asset class
     * ({@code StrategyProperties.bookFor}), so "unwinding" a hedge position would open an offsetting
     * one in a STRATEGY book: two legs where there was one, gross exposure up, and the two loops
     * fighting each other every cycle. The hedger already shrinks its own leg as the strategy books
     * flatten, which is the correct direction of causality.
     */
    private static java.util.Set<String> heldInRoutedBooks(ObjectProvider<RiskProjection> risk, String hedgeBook) {
        RiskProjection projection = risk.getIfAvailable();
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (projection == null) {
            return out;
        }
        for (PositionRisk p : projection.snapshot(System.currentTimeMillis()).positions()) {
            if (p.quantity().signum() == 0 || (hedgeBook != null && hedgeBook.equalsIgnoreCase(p.bookId()))) {
                continue;
            }
            out.add(p.instrumentId());
        }
        return out;
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
