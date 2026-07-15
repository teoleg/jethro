package io.jethro.app.risk;

import io.jethro.app.kafka.KafkaConfig;
import io.jethro.app.kafka.KafkaEventPublisher;
import io.jethro.refdata.RefDataRepository;
import io.jethro.trading.riskpnl.CurveService;
import io.jethro.trading.riskpnl.InstrumentRefSource;
import io.jethro.trading.riskpnl.PreTradeGuardrail;
import io.jethro.trading.riskpnl.RiskLimitEvaluator;
import io.jethro.trading.riskpnl.RiskLimitSource;
import io.jethro.trading.riskpnl.RiskProjection;
import io.jethro.uigateway.AttentionFeed;
import io.jethro.uigateway.SseBroadcaster;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.Optional;

/**
 * Risk-pnl assembly wiring (ADR-0015). The projection and REST surface are always present
 * so the UI has an endpoint even before a fill exists; the broker-facing pieces (fills/
 * marks consumer, risk.snapshots publisher) exist only when jethro.kafka.enabled. The
 * instrument reference source binds to reference-data when persistence is on, else an
 * empty source (risk-pnl falls back to multiplier 1).
 */
@Configuration
@EnableConfigurationProperties(RiskLimitProperties.class)
public class RiskConfig {

    @Bean
    InstrumentRefSource instrumentRefSource(ObjectProvider<RefDataRepository> refData) {
        RefDataRepository repository = refData.getIfAvailable();
        if (repository != null) {
            return new RefDataInstrumentRefSource(repository);
        }
        return instrumentId -> Optional.empty();
    }

    /** Swap tenor from reference data (V27 tenor_years) — the single source used by the DV01,
     *  scenario, VaR and swap-book paths; no hardcoded per-name tenor map (GAP-4). */
    @Bean
    SwapTenorSource swapTenorSource(ObjectProvider<RefDataRepository> refData) {
        RefDataRepository repository = refData.getIfAvailable();
        return repository != null ? SwapTenorSource.from(repository) : SwapTenorSource.NONE;
    }

    /** The ledger with LIVE swap economics: SWAP positions value at the Strata per-lot DV01
     *  × 100 (re-read on a 1s memo — off the tick path) instead of the V9 inception-constant
     *  multiplier; falls back to the static multiplier until the curve prices. */
    @Bean
    RiskProjection riskProjection(InstrumentRefSource refs,
                                  io.jethro.trading.riskpnl.SwapPricingService swapPricing) {
        var cache = new java.util.concurrent.atomic.AtomicReference<Map.Entry<Long, Map<String, java.math.BigDecimal>>>();
        io.jethro.trading.riskpnl.SwapDv01Source dv01 = instrumentId -> {
            long now = System.currentTimeMillis();
            var entry = cache.get();
            if (entry == null || now - entry.getKey() > 1_000) {
                Map<String, java.math.BigDecimal> fresh = new java.util.LinkedHashMap<>();
                for (var v : swapPricing.valueAll(java.time.LocalDate.now())) {
                    fresh.put(v.instrumentId(), v.dv01());
                }
                entry = Map.entry(now, fresh);
                cache.set(entry);
            }
            return Optional.ofNullable(entry.getValue().get(instrumentId));
        };
        return new RiskProjection(refs, dv01);
    }

    /** Live bond-future durations off the Treasury curve (dynamic DV01); static refdata
     *  durations until it quotes. */
    @Bean
    io.jethro.trading.riskpnl.BondFutureDurations bondFutureDurations(
            io.jethro.trading.riskpnl.TreasuryCurveView treasuryCurveView, InstrumentRefSource refs) {
        return new io.jethro.trading.riskpnl.BondFutureDurations(treasuryCurveView, refs);
    }

    /** Live SOFR curve from streamed tenor quotes (quant-engine phase 4). */
    @Bean
    CurveService curveService() {
        return new CurveService();
    }

    /** The DISTINCT US Treasury par curve (USD.TSY.* marks) — swap spread visible vs SOFR. */
    @Bean
    io.jethro.trading.riskpnl.TreasuryCurveView treasuryCurveView() {
        return new io.jethro.trading.riskpnl.TreasuryCurveView();
    }

    /** Strata swap valuation (PV/DV01/par) on the live curve. */
    @Bean
    io.jethro.trading.riskpnl.SwapPricingService swapPricingService(CurveService curveService) {
        return new io.jethro.trading.riskpnl.SwapPricingService(curveService);
    }

    /** Deterministic scenario/stress over live positions (quant-engine step 2). Rates legs
     *  are FULL revaluation on the shifted curve: bond futures re-price the CTD par bond
     *  (convexity), swaps re-price the trade-dated book's seasoned trades (aging + convexity)
     *  via the read port below — with the engine's stated first-order fallbacks. */
    @Bean
    io.jethro.trading.riskpnl.ScenarioEngine scenarioEngine(InstrumentRefSource refs,
                                                            io.jethro.trading.riskpnl.SwapPricingService swapPricing,
                                                            io.jethro.trading.riskpnl.BondFutureDurations durations,
                                                            Dv01Service.SwapTradeSource swapTrades,
                                                            SwapTenorSource tenors,
                                                            ObjectProvider<io.jethro.app.session.TradingCalendar> calendar) {
        var cal = calendar.getIfAvailable();
        java.util.function.Supplier<java.time.LocalDate> sessionDay =
                cal != null ? cal::sessionDay : java.time.LocalDate::now;
        io.jethro.trading.riskpnl.ScenarioEngine.SeasonedSwapReval seasoned = (book, instrument, shiftBps) -> {
            var tenor = tenors.tenorYears(instrument).orElse(null);
            if (tenor == null) {
                return Optional.empty(); // unknown product — engine falls back, never guesses
            }
            java.time.LocalDate valuation = sessionDay.get();
            java.math.BigDecimal total = java.math.BigDecimal.ZERO;
            boolean any = false;
            for (var t : swapTrades.trades()) {
                if (!book.equals(t.book()) || !instrument.equals(t.instrument())) {
                    continue;
                }
                var pnl = swapPricing.seasonedPnlUnderShock(t.tradeDay(), tenor,
                        "BUY".equals(t.side()),                       // V9: BUY = pay fixed
                        t.entryPar().movePointLeft(2).doubleValue(),  // par % → fraction
                        t.lots().doubleValue() * 1_000_000.0, valuation, shiftBps);
                if (pnl.isEmpty()) {
                    return Optional.empty(); // curve died mid-loop — whole leg falls back
                }
                total = total.add(pnl.get());
                any = true;
            }
            return any ? Optional.of(total) : Optional.empty();
        };
        return new io.jethro.trading.riskpnl.ScenarioEngine(refs, swapPricing, durations, seasoned);
    }


    @Bean
    RiskController riskController(RiskProjection projection, CurveService curveService,
                                  io.jethro.trading.riskpnl.TreasuryCurveView treasuryCurveView,
                                  io.jethro.trading.riskpnl.SwapPricingService swapPricing,
                                  io.jethro.trading.riskpnl.ScenarioEngine scenarioEngine) {
        return new RiskController(projection, curveService, treasuryCurveView, swapPricing,
                scenarioEngine);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "jethro.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
    RiskDataConsumer riskDataConsumer(KafkaConfig.JethroKafkaProperties properties, RiskProjection projection,
                                      CurveService curveService,
                                      io.jethro.trading.riskpnl.TreasuryCurveView treasuryCurveView,
                                      ObjectProvider<SwapTradeRecorder> swapTrades,
                                      ObjectProvider<org.springframework.jdbc.core.JdbcTemplate> jdbc) {
        SwapTradeRecorder recorder = swapTrades.getIfAvailable();
        // Seed the projection from the fills TABLE (source of truth, never retention-bound);
        // the topic then supplies live increments only. Without a DB, seed from nothing.
        var template = jdbc.getIfAvailable();
        FillHistorySource history = template != null ? FillHistorySource.jdbc(template) : FillHistorySource.NONE;
        var consumer = new RiskDataConsumer(properties.bootstrapServers(), projection, curveService,
                treasuryCurveView, recorder != null ? recorder::onFill : null, history);
        consumer.start();
        return consumer;
    }

    /** Trade-dated swap registry (V23) — feeds the seasoned swap book; needs DB + calendar. */
    @Bean
    @ConditionalOnProperty(prefix = "jethro.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
    SwapTradeRecorder swapTradeRecorder(org.springframework.jdbc.core.JdbcTemplate jdbc,
                                        InstrumentRefSource refs,
                                        ObjectProvider<io.jethro.app.session.TradingCalendar> calendar) {
        var cal = calendar.getIfAvailable();
        return new SwapTradeRecorder(jdbc, refs,
                cal != null ? cal::sessionDay : java.time.LocalDate::now);
    }

    /** Seasoned (trade-dated) swap book valuation — the precise rates-desk view. */
    @Bean
    @ConditionalOnProperty(prefix = "jethro.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
    SwapBookService swapBookService(org.springframework.jdbc.core.JdbcTemplate jdbc,
                                    io.jethro.trading.riskpnl.SwapPricingService swapPricing,
                                    SwapTenorSource tenors,
                                    ObjectProvider<io.jethro.app.session.TradingCalendar> calendar) {
        var cal = calendar.getIfAvailable();
        return new SwapBookService(jdbc, swapPricing, tenors,
                cal != null ? cal::sessionDay : java.time.LocalDate::now);
    }

    /** swap_trades read side for the DV01 view; NONE (swap legs absent, disclosed) without
     *  persistence — the futures legs still report off the projection. */
    @Bean
    Dv01Service.SwapTradeSource swapTradeSource(
            ObjectProvider<org.springframework.jdbc.core.JdbcTemplate> jdbc) {
        var template = jdbc.getIfAvailable();
        if (template == null) {
            return Dv01Service.SwapTradeSource.NONE;
        }
        return () -> template.query("""
                select book, instrument, side, lots, entry_par, trade_day from swap_trades
                """, (rs, i) -> new Dv01Service.SwapTradeSource.Trade(
                rs.getString("book"), rs.getString("instrument"), rs.getString("side"),
                rs.getBigDecimal("lots"), rs.getBigDecimal("entry_par"),
                rs.getObject("trade_day", java.time.LocalDate.class)));
    }

    /** Bucketed key-rate DV01 per book (quant-engine step 4). */
    @Bean
    Dv01Service dv01Service(RiskProjection projection,
                            io.jethro.trading.riskpnl.SwapPricingService swapPricing,
                            io.jethro.trading.riskpnl.BondFutureDurations durations,
                            Dv01Service.SwapTradeSource swapTrades,
                            SwapTenorSource tenors,
                            ObjectProvider<io.jethro.app.session.TradingCalendar> calendar) {
        var cal = calendar.getIfAvailable();
        return new Dv01Service(projection, swapPricing, durations, swapTrades, tenors,
                cal != null ? cal::sessionDay : java.time.LocalDate::now);
    }

    @Bean
    Dv01Controller dv01Controller(Dv01Service service) {
        return new Dv01Controller(service);
    }

    @Bean
    @ConditionalOnProperty(prefix = "jethro.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
    RiskSnapshotPublisher riskSnapshotPublisher(RiskProjection projection, KafkaEventPublisher publisher) {
        return new RiskSnapshotPublisher(projection, publisher);
    }

    @Bean
    RiskLimitSource riskLimitSource(RiskLimitProperties properties) {
        return new ConfiguredRiskLimitSource(properties);
    }

    @Bean
    RiskLimitEvaluator riskLimitEvaluator(RiskLimitProperties properties) {
        return new RiskLimitEvaluator(properties.warnRatioOrDefault());
    }

    /** Pre-trade exposure guardrail (ADR-0018), consumed by the order module via its port. */
    @Bean
    PreTradeGuardrail preTradeGuardrail(RiskProjection projection, RiskLimitSource limits) {
        return new PreTradeGuardrail(projection, limits);
    }

    /** The firm circuit breaker's switch (ADR-0027) — always present so the strategy and
     *  autonomy can consult it even when the monitor is disabled. */
    @Bean
    TradingHaltSwitch tradingHaltSwitch() {
        return new TradingHaltSwitch();
    }

    /** Measured per-instrument daily vol from recorded daily closes — vol-targeted sizing
     *  for the strategy AND the AI sleeve reads this; needs the DB. */
    @Bean
    @ConditionalOnProperty(prefix = "jethro.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
    InstrumentVolSource instrumentVolSource(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        return new InstrumentVolService(jdbc);
    }

    /** Measured instrument↔portfolio correlation for covariance-aware sizing; NONE until
     *  the daily history warms up (callers fall back to standalone vol-targeting). */
    @Bean
    PortfolioCorrelationSource portfolioCorrelationSource(ObjectProvider<VarService> varService) {
        return instrumentId -> {
            VarService service = varService.getIfAvailable();
            return service != null ? service.correlationToPortfolio(instrumentId) : Optional.empty();
        };
    }

    /** Historical-simulation VaR over recorded daily closes (ADR-0027); needs the DB.
     *  Swap positions enter as seasoned-DV01 × Δbp synthetic legs (rates VaR). */
    @Bean
    @ConditionalOnProperty(prefix = "jethro.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
    VarService varService(org.springframework.jdbc.core.JdbcTemplate jdbc, RiskProjection projection,
                          InstrumentRefSource refs,
                          io.jethro.trading.riskpnl.SwapPricingService swapPricing,
                          Dv01Service.SwapTradeSource swapTrades,
                          SwapTenorSource tenors,
                          ObjectProvider<io.jethro.app.session.TradingCalendar> calendar) {
        var cal = calendar.getIfAvailable();
        return new VarService(jdbc, projection, refs, swapPricing, swapTrades, tenors,
                cal != null ? cal::sessionDay : java.time.LocalDate::now);
    }

    /** Records daily closes + firm equity — the history VaR and the breaker peak run on.
     *  Day labels come from the session calendar (ADR-0027); the cadence samples a compressed
     *  sim day several times (min 5s) and stays at 60s for wall-clock days. */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "jethro.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
    MarketHistoryRecorder marketHistoryRecorder(org.springframework.jdbc.core.JdbcTemplate jdbc,
                                                ObjectProvider<io.jethro.app.trading.TradingCoreLifecycle> tradingCore,
                                                ObjectProvider<io.jethro.app.session.TradingCalendar> calendar,
                                                ObjectProvider<io.jethro.app.trading.TradingCoreProperties> tradingProps,
                                                RiskProjection projection) {
        var core = tradingCore.getIfAvailable();
        var cal = calendar.getIfAvailable();
        var props = tradingProps.getIfAvailable();
        boolean simDays = props != null && "sim".equals(props.providerOrDefault());
        long period = simDays
                ? Math.max(5, Math.round(props.simSecondsPerDayOrDefault() / 10.0))
                : 60;
        var recorder = new MarketHistoryRecorder(jdbc, core,
                () -> projection.snapshot(System.currentTimeMillis()).total().totalPnl(),
                cal != null ? cal::sessionDay : java.time.LocalDate::now, period);
        if (core != null) {
            recorder.start(); // trading off → nothing to record, never scheduled
        }
        return recorder;
    }

    /** Firm max-drawdown breaker (ADR-0027): halts all auto-execution, operator reset only. */
    @Bean(destroyMethod = "close")
    FirmBreakerMonitor firmBreakerMonitor(RiskProjection projection, RiskLimitProperties limits,
                                          TradingHaltSwitch haltSwitch, AttentionFeed feed, SseBroadcaster sse,
                                          ObjectProvider<org.springframework.jdbc.core.JdbcTemplate> jdbc) {
        var monitor = new FirmBreakerMonitor(projection, limits.maxFirmDrawdown(), haltSwitch,
                feed, sse, jdbc.getIfAvailable());
        monitor.start();
        return monitor;
    }

    /** Risk-limit breaches feed the attention floor; needs live fills, so gated on the broker. */
    @Bean
    @ConditionalOnProperty(prefix = "jethro.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
    RiskLimitMonitor riskLimitMonitor(RiskProjection projection, RiskLimitEvaluator evaluator,
                                      RiskLimitSource limits, AttentionFeed feed, SseBroadcaster sse) {
        return new RiskLimitMonitor(projection, evaluator, limits, feed, sse);
    }

    /** Worst-stress-vs-loss-cap attention trigger (deterministic floor, ADR-0017). */
    @Bean
    @ConditionalOnProperty(prefix = "jethro.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
    ScenarioMonitor scenarioMonitor(RiskProjection projection,
                                    io.jethro.trading.riskpnl.ScenarioEngine scenarioEngine,
                                    RiskLimitProperties limits, AttentionFeed feed, SseBroadcaster sse) {
        return new ScenarioMonitor(projection, scenarioEngine, limits, feed, sse);
    }
}
