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
import java.util.Collection;
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
            @Value("${jethro.fusion.forecast-scalar.enabled:true}") boolean forecastScalarEnabled,
            @Value("${jethro.fusion.forecast-scalar.min-sample:30}") int forecastScalarMinSample,
            @Value("${jethro.fusion.freshness-seconds:600}") long freshnessSeconds) {
        var params = new ForecastRegistry.Params(expectedAbsZ, convictionStep, socialPerChannel, learnedScale);
        // ADR-0092: every continuous source's scaling constant above is a CLAIM about how big its
        // readings are. This measures the claim on the running stream and scales the source back to
        // TARGET_ABS when it over-delivers — one-way, so an estimate can only ever shrink the book.
        var scalars = new ForecastScalars(forecastScalarEnabled, forecastScalarMinSample);
        return new ForecastRegistry(params, freshnessSeconds * 1_000, scalars);
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
                                    io.jethro.trading.riskpnl.InstrumentRefSource refs,
                                    ObjectProvider<RiskProjection> risk,
                                    ObjectProvider<io.jethro.app.risk.VarService> varService,
                                    ObjectProvider<FusionExecutor> executor,
                                    @org.springframework.beans.factory.annotation.Qualifier("sharedScheduler") java.util.concurrent.ScheduledExecutorService scheduler,
                                    @Value("${jethro.fusion.assumed-correlation:0.5}") double assumedCorrelation,
                                    @Value("${jethro.fusion.unit-notional-usd:10000}") BigDecimal unitNotional,
                                    @Value("${jethro.fusion.buffer-fraction:0.2}") double bufferFraction,
                                    @Value("${jethro.fusion.adjustment-rate:0}") double adjustmentRate,
                                    @Value("${jethro.signals.horizon-seconds:3600}") long evidenceHorizonSeconds,
                                    @Value("${jethro.fusion.route-orders:false}") boolean routeOrders,
                                    @Value("${jethro.fusion.interval-seconds:30}") long intervalSeconds,
                                    @Value("${jethro.fusion.min-forecast-to-route:5.0}") double minForecastToRoute,
                                    ObjectProvider<io.jethro.app.signal.SignalTelemetry> telemetry,
                                    @Value("${jethro.fusion.weights.mode:telemetry}") String weightsMode,
                                    @Value("${jethro.fusion.weights.shrinkage-k:20}") double shrinkageK,
                                    @Value("${jethro.fusion.weights.min:0.25}") double weightMin,
                                    @Value("${jethro.fusion.weights.max:3.0}") double weightMax,
                                    ObjectProvider<io.jethro.order.ExecutionQualityRepository> tca,
                                    @Value("${jethro.fusion.edge-gate.enabled:true}") boolean edgeGateEnabled,
                                    @Value("${jethro.fusion.edge-gate.min-sample:30}") int edgeGateMinSample,
                                    @Value("${jethro.fusion.edge-gate.t-hurdle:2.0}") double edgeGateTHurdle,
                                    @Value("${jethro.fusion.vol-budget.winsor-pct:10.0}") double volBudgetWinsorPct,
                                    ObjectProvider<io.jethro.uigateway.MarkHistory> markHistory,
                                    @Value("${jethro.fusion.risk-cut.enabled:true}") boolean riskCutEnabled,
                                    @Value("${jethro.fusion.risk-cut.sigma-multiple:3.0}") double riskCutSigmaMultiple,
                                    @Value("${jethro.fusion.risk-cut.vol-span:120}") int riskCutVolSpan,
                                    @Value("${jethro.fusion.stream-covariance.enabled:true}") boolean streamCovEnabled,
                                    @Value("${jethro.fusion.stream-covariance.span:120}") int streamCovSpan,
                                    @Value("${jethro.hedge.book:HEDGE}") String hedgeBook) {
        // ADR-0080: the trading rate is DERIVED, not dialled — it is the fraction that makes the
        // desk's exposure e-fold toward target in exactly one signal-evidence horizon, so the return
        // the edge gate credits and the round-trip cost it charges are denominated over the same
        // trade. ADR-0082 makes that horizon the one the EVIDENCE picked rather than a fixed dial, so
        // the derivation moves into the loop, where the selected rung is known: a rate of 0 in Params
        // means DERIVE PER CYCLE, and a positive explicit value still pins it (escape hatch).
        var params = new FusionPlanner.Params(assumedCorrelation, unitNotional, bufferFraction,
                Math.max(0, adjustmentRate));
        // ADR-0055 item 6: per-source weights are re-estimated from the phase-1 telemetry each cycle
        // (evidence, not decree), shrunk toward equal so a thin sample can't dominate. mode=equal forces
        // the flat placeholder; telemetry (default) falls back to equal when the store is absent or cold.
        var weightParams = new TelemetryWeights.Params(shrinkageK, weightMin, weightMax);
        // ADR-0082: the gate's significance test is now a search over the measurement ladder, so the
        // α it is read against carries the Bonferroni haircut for the number of rungs searched. The
        // ladder is read off the telemetry itself rather than restated here, so the two cannot drift.
        var gateParams = new EdgeGate.Params(edgeGateMinSample, edgeGateTHurdle,
                telemetryRungs(telemetry));
        // ADR-0082: ONE rung selection per cycle drives everything downstream — the gate's verdict, the
        // per-source weights, and (in FusionLifecycle) the holding period. Both suppliers recompute it
        // from the same live telemetry with the same pure function, so they cannot disagree; the desk
        // must never grade a source over one period, weight it over a second and hold it for a third.
        java.util.function.Supplier<FusionWeights> weightsSupplier =
                "equal".equalsIgnoreCase(weightsMode)
                        ? FusionWeights::equal
                        : () -> {
                            var selection = selectRung(telemetry, tca, gateParams);
                            return selection == null ? FusionWeights.equal()
                                    : FusionWeights.fromTelemetry(selection.stats(), weightParams);
                        };
        // ADR-0064: the edge gate re-reads BOTH measurements every cycle — per-source realised
        // expectancy (signal telemetry) and the desk's own realised slippage (TCA) — so it opens by
        // itself the moment a source earns its cost, and closes again if that decays. Nothing here is
        // a chosen number: the only dials are the significance hurdle and the minimum sample.
        java.util.function.Supplier<EdgeGate.Decision> gateSupplier = !edgeGateEnabled ? null
                : () -> {
                    if (tca.getIfAvailable() == null) {
                        return null; // no measurement path — leave the pre-existing controls alone
                    }
                    var selection = selectRung(telemetry, tca, gateParams);
                    return selection == null ? null : selection.decision();
                };
        // ADR-0079: the same EWMA daily-return covariance the parametric VaR and the ADR-0038 hedge
        // advisor already price risk with — so the sizer and the risk engine cannot disagree about how
        // correlated the book is. Re-read each cycle (VarService memoises it for 30s), and a warm-up,
        // an absent estimate or a failed read all degrade to NONE, which leaves the book exactly as
        // planned rather than asserting a correlation nothing measured.
        java.util.function.Supplier<ReturnCovarianceSource> covarianceSupplier = () -> {
            var v = varService.getIfAvailable();
            if (v == null) {
                return ReturnCovarianceSource.NONE;
            }
            try {
                return v.covarianceSnapshot().map(FusionConfig::returnCovariance)
                        .orElse(ReturnCovarianceSource.NONE);
            } catch (RuntimeException e) {
                return ReturnCovarianceSource.NONE;
            }
        };
        var lifecycle = new FusionLifecycle(registry,
                instrument -> priceFor(tradingCore, instrument),
                // ADR-0078: the contract spec comes from the instrument master — the same source
                // PositionRisk values the resulting exposure with. A name absent from the master has no
                // spec to size against, so it plans flat (and the executor vetoes it in any case).
                instrument -> refs.find(instrument).map(io.jethro.trading.riskpnl.InstrumentRef::multiplier)
                        .orElse(null),
                // ADR-0091: net against the books this layer ROUTES INTO, not against the hedge
                // overlay. The two suppliers below must agree about which positions are the desk's
                // own; when the quantity read included the hedge book and the span set did not, every
                // contract the hedger bought was answered by an equal, opposite one opened in a
                // strategy book — the ratchet the span set's own javadoc warns about, reached by the
                // other route.
                () -> routedBookPositions(risk, hedgeBook),
                () -> heldInRoutedBooks(risk, hedgeBook),
                weightsSupplier, params, routeOrders, executor.getIfAvailable(), scheduler, intervalSeconds,
                minForecastToRoute, gateSupplier, covarianceSupplier, evidenceHorizonSeconds,
                volBudgetWinsorPct,
                // ADR-0086: the desk's risk-reactive exit. σ is measured from the MARK STREAM rather
                // than from daily closes because the daily estimate covers a handful of names and none
                // of the ones the desk holds, so a control keyed on it would be silent exactly where it
                // is needed. Disabled ⇒ both are null and the book is byte-identical to before.
                riskCutEnabled ? new StreamVolatility(new StreamVolatility.Params(riskCutVolSpan)) : null,
                riskCutEnabled ? new TrailingRiskCut(new TrailingRiskCut.Params(riskCutSigmaMultiple)) : null,
                storedPrices(markHistory),
                instrument -> markTimeFor(tradingCore, instrument),
                // ADR-0089: the correlation the two sizing controls above are measured with. The
                // daily-close covariance they were built on covers an instrument only after several
                // admissible sessions in the RUNNING feed mode (ADR-0073), so on a young stream it
                // covers none of the planned book and both controls fall silent — the desk then
                // carries one per-name budget for every name of what may be a single bet. Measured
                // here on the mark stream instead, the same series ADR-0086's σ already had to fall
                // back to for the same reason. Disabled ⇒ null and the book is byte-identical.
                streamCovEnabled ? new StreamCovariance(new StreamCovariance.Params(streamCovSpan)) : null);
        lifecycle.start();
        return lifecycle;
    }

    /**
     * The PROVIDER timestamp of a name's current mark — the seed anchor for the ADR-0086 σ sensor. The
     * store is keyed by provider time, so anchoring the seed on wall clock silently empties it on any
     * delayed, replayed or simulated feed (the ADR-0071 correction; one clock only).
     */
    private static Long markTimeFor(ObjectProvider<TradingCoreLifecycle> tradingCore, String instrument) {
        TradingCoreLifecycle core = tradingCore.getIfAvailable();
        if (core == null || core.runtime() == null) {
            return null;
        }
        var holder = core.runtime().markCache().get(instrument);
        return holder == null ? null : holder.providerTimestampMillis();
    }

    /**
     * How many rungs the live telemetry is measuring over — the multiplicity the edge gate's α must be
     * divided by (ADR-0082). Read off the telemetry rather than from the property, so the count the
     * search is charged for is always the count actually searched. One when telemetry is absent, which
     * is exactly the pre-ADR-0082 α.
     */
    private static int telemetryRungs(ObjectProvider<io.jethro.app.signal.SignalTelemetry> telemetry) {
        var t = telemetry.getIfAvailable();
        return t == null ? 1 : Math.max(1, t.horizons().size());
    }

    /**
     * The measurement horizon the evidence picks this cycle, with the gate decision made on it
     * (ADR-0082). Pure given the telemetry snapshot, so every caller in a cycle agrees without any
     * shared state between them.
     *
     * <p>A missing TCA reading is passed through as {@code null} rather than as a cost: nothing has
     * filled in this feed mode yet, the gate stays open on its own terms, and inventing a cost would be
     * a number without provenance. A failed read returns null and leaves the pre-existing controls
     * alone — telemetry must never stop the planning loop.
     */
    private static HorizonLadder.Selection selectRung(
            ObjectProvider<io.jethro.app.signal.SignalTelemetry> telemetry,
            ObjectProvider<io.jethro.order.ExecutionQualityRepository> tca,
            EdgeGate.Params gateParams) {
        var t = telemetry.getIfAvailable();
        if (t == null) {
            return null;
        }
        try {
            var q = tca.getIfAvailable();
            Double roundTripBps = q == null ? null
                    : q.averageSlippageBps().map(oneWay -> oneWay.doubleValue() * 2.0).orElse(null);
            var costs = q == null ? java.util.Map.<String, Double>of() : roundTripByInstrument(q);
            return HorizonLadder.select(t.statsByHorizon(), roundTripBps, costs, gateParams);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Adapts a {@link io.jethro.trading.riskpnl.CovMath.Covariance} snapshot to the fusion layer's
     * narrow read interface (ADR-0079). The name→index map is built once per snapshot so the pairwise
     * walk over the target book is a hash lookup rather than a linear scan; a name outside the
     * estimate's strict-coverage intersection reads empty, and the caller makes no claim about it.
     */
    private static ReturnCovarianceSource returnCovariance(io.jethro.trading.riskpnl.CovMath.Covariance cov) {
        java.util.List<String> names = cov.instruments();
        java.util.Map<String, Integer> index = new java.util.HashMap<>(names.size() * 2);
        for (int i = 0; i < names.size(); i++) {
            index.put(names.get(i), i);
        }
        double[][] sigma = cov.sigma();
        return (a, b) -> {
            Integer i = index.get(a);
            Integer j = index.get(b);
            return i == null || j == null
                    ? java.util.OptionalDouble.empty()
                    : java.util.OptionalDouble.of(sigma[i][j]);
        };
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
     * The desk's MEASURED round-trip execution cost per instrument (ADR-0072): twice the
     * implementation-shortfall slippage its own fills in this feed mode actually incurred, which is the
     * same construction the desk-wide hurdle uses — only not blended across names that cost two orders
     * of magnitude apart.
     *
     * <p>Price-quoted names only. A rate-quoted instrument's slippage "bp" is an additive basis point of
     * RATE, not a fraction of notional, so it is not comparable with an expectancy expressed in bps of
     * price; those rows are excluded rather than silently blended (the same unit rule
     * {@code averageSlippageBps} applies). A name with no fill in this mode simply does not appear —
     * absence means "not measured", and the gate asserts no cost for it.
     */
    private static java.util.Map<String, Double> roundTripByInstrument(
            io.jethro.order.ExecutionQualityRepository tca) {
        var out = new java.util.HashMap<String, Double>();
        for (var a : tca.aggregates()) {
            if (a.rateQuoted() || a.instrument() == null || a.avgSlippageBps() == null) {
                continue;
            }
            out.put(a.instrument(), a.avgSlippageBps().doubleValue() * 2.0);
        }
        return out;
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

    /**
     * Net quantity per instrument across the books this layer ROUTES INTO — every book except the
     * hedge (ADR-0091). Exact decimal throughout: a plain signed sum of the projection's quantities,
     * no rounding introduced.
     *
     * <p><b>Why the hedge book is excluded.</b> This map is the {@code current} the planner measures
     * its gap against, so it defines what the desk considers its own inventory. The hedge book is not
     * inventory: it is the ADR-0019/0039 hedger's own continuously re-targeted leg, held against the
     * strategy books' residual exposure, and this layer cannot trade it — it routes by asset class
     * into the strategy books. Counting it as inventory makes the two loops a closed positive
     * feedback: the hedger buys `h` of the proxy to offset the strategy books; the planner reads its
     * gap as `target − (own + h)` and opens an extra `−h` in a STRATEGY book to close it; the hedger's
     * own target is unchanged by that, so nothing converges. Both legs grow together, the firm ends up
     * long and short the same contract in size, gross exposure carries a hedge that has been exactly
     * cancelled, and both books pay the spread on every step.
     *
     * <p>Measured on the live book at the time of writing: the hedger held +0.050697 ES against the
     * strategy books' short cash equities while the planner, reading a firm net of +0.011200, was
     * selling ES into MACRO toward a target of −0.160100. At convergence the old read leaves MACRO at
     * {@code target − h = −0.210797} and the new read leaves it at {@code target = −0.160100} — the
     * difference is exactly {@code h}, i.e. the hedge's own notional double-counted as gross, and the
     * hedge's offset restored to the firm's net rather than being traded away.
     *
     * <p>The two suppliers passed to the lifecycle now answer the same question the same way: {@link
     * #heldInRoutedBooks} decides WHICH names the desk is responsible for, this decides HOW MUCH of
     * each it holds. Any instrument the hedge book does not hold is unaffected, quantity for quantity.
     */
    private static Map<String, BigDecimal> routedBookPositions(ObjectProvider<RiskProjection> risk,
                                                               String hedgeBook) {
        RiskProjection projection = risk.getIfAvailable();
        if (projection == null) {
            return new HashMap<>();
        }
        ConsolidatedRisk snap = projection.snapshot(System.currentTimeMillis());
        return routedBookPositions(snap.positions(), hedgeBook);
    }

    /** The pure part of {@link #routedBookPositions(ObjectProvider, String)} — see its javadoc. */
    static Map<String, BigDecimal> routedBookPositions(Collection<PositionRisk> positions,
                                                       String hedgeBook) {
        Map<String, BigDecimal> out = new HashMap<>();
        if (positions == null) {
            return out;
        }
        for (PositionRisk p : positions) {
            if (hedgeBook != null && hedgeBook.equalsIgnoreCase(p.bookId())) {
                continue;
            }
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
