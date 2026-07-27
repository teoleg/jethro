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
            @Value("${jethro.hedge.equity-proxy-multiplier:50}") BigDecimal equityProxyMultiplier) {
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
                proxyCandidates, proxySwitchMargin, equityProxy, multiplierOf);
    }

    /** AUTO-hedge executor (ADR-0039): submits the hedge DELTA in AUTO mode, sim-gated. The hedge
     *  trades in its own dedicated book (default HEDGE) so its position is unambiguous feedback
     *  for the advisor and never collides with the strategy's own futures (which route to MACRO). */
    @Bean(destroyMethod = "stop")
    HedgeLifecycle hedgeLifecycle(HedgeAdvisor advisor, ObjectProvider<VarService> varService,
                                  ObjectProvider<InstrumentRefSource> refs, ObjectProvider<LastPriceCache> prices,
                                  ObjectProvider<OrderService> orderService, ObjectProvider<TradingHaltSwitch> haltSwitch,
                                  ObjectProvider<io.jethro.trading.riskpnl.RiskProjection> projection,
                                  ObjectProvider<io.jethro.app.trading.TradingCoreLifecycle> tradingCore,
                                  ObjectProvider<io.jethro.uigateway.MarkHistory> markHistory,
                                  @Value("${jethro.hedge.book:HEDGE}") String hedgeBook,
                                  @Value("${jethro.hedge.cooldown-seconds:60}") long cooldownSeconds,
                                  @Value("${jethro.hedge.interval-seconds:5}") long intervalSeconds,
                                  @Value("${jethro.hedge.stream-covariance.enabled:true}") boolean streamCovEnabled,
                                  @Value("${jethro.hedge.stream-covariance.span:120}") int streamCovSpan) {
        var streamCov = streamCovEnabled
                ? new HedgeStreamCovariance(
                        new io.jethro.app.fusion.StreamCovariance(
                                new io.jethro.app.fusion.StreamCovariance.Params(streamCovSpan)),
                        storedPrices(markHistory), instrument -> markTimeFor(tradingCore, instrument),
                        intervalSeconds * 1_000L)
                : null;
        var lifecycle = new HedgeLifecycle(advisor, varService, refs, prices, orderService, haltSwitch,
                projection, tradingCore, hedgeBook, cooldownSeconds, intervalSeconds, streamCov);
        lifecycle.start();
        return lifecycle;
    }

    /**
     * The PROVIDER timestamp of a name's current mark — the anchor for the ADR-0095 warm-restart
     * seed. The store is keyed by provider time, so anchoring on wall clock silently empties the seed
     * on any delayed, replayed or simulated feed (the ADR-0071 correction: one clock, the feed's).
     */
    private static Long markTimeFor(
            ObjectProvider<io.jethro.app.trading.TradingCoreLifecycle> tradingCore, String instrument) {
        var core = tradingCore.getIfAvailable();
        if (core == null || core.runtime() == null) {
            return null;
        }
        var holder = core.runtime().markCache().get(instrument);
        return holder == null ? null : holder.providerTimestampMillis();
    }

    /**
     * Adapts the durable chart price history to the warm-restart seed (ADR-0071/0089/0095) — the same
     * {@code md.marks} series the hedge samples live, already persisted and already surviving a
     * restart (ADR-0014 derived data). Absent (in-memory profile, tests) → the estimator cold-starts
     * and the structural tier carries the book until it warms, exactly as today.
     */
    private static io.jethro.app.fusion.SensorWarmup.History storedPrices(
            ObjectProvider<io.jethro.uigateway.MarkHistory> provider) {
        io.jethro.uigateway.MarkHistory history = provider.getIfAvailable();
        if (history == null) {
            return null;
        }
        return (instrumentId, sinceMillis) -> {
            var out = new java.util.ArrayList<io.jethro.app.fusion.SensorWarmup.Point>();
            for (var point : history.since(instrumentId, sinceMillis)) {
                try {
                    out.add(new io.jethro.app.fusion.SensorWarmup.Point(
                            point.t(), new BigDecimal(point.price())));
                } catch (NumberFormatException | NullPointerException e) {
                    // a single unparseable stored price must not cost the whole seed
                }
            }
            return out;
        };
    }
}

