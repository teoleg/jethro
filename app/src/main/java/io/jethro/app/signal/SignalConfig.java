package io.jethro.app.signal;

import io.jethro.app.trading.TradingCoreLifecycle;
import io.jethro.domain.Decimals;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Wires ADR-0055 phase-1 signal telemetry: a rebuildable, feed-mode-scoped record of every source's
 * directional call, scored by realised forward return. DB-backed, so gated on persistence and on
 * {@code jethro.signals.enabled} (default true). The mark source is the live trading-core cache —
 * read-only. This layer only measures; it never sizes, gates, or orders (ADR-0016 / invariant 7).
 */
@Configuration
@ConditionalOnProperty(prefix = "jethro.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SignalConfig {

    @Bean
    @ConditionalOnProperty(prefix = "jethro.signals", name = "enabled", havingValue = "true", matchIfMissing = true)
    SignalTelemetryStore signalTelemetryStore(JdbcTemplate jdbc) {
        return new SignalTelemetryStore(jdbc);
    }

    @Bean
    @ConditionalOnProperty(prefix = "jethro.signals", name = "enabled", havingValue = "true", matchIfMissing = true)
    SignalTelemetry signalTelemetry(SignalTelemetryStore store,
                                    ObjectProvider<TradingCoreLifecycle> tradingCore,
                                    @Value("${jethro.signals.horizon-seconds:3600}") int horizonSeconds,
                                    @Value("${jethro.signals.horizon-rungs:3}") int horizonRungs,
                                    @Value("${jethro.signals.flat-threshold-bps:10}") double flatThresholdBps,
                                    @Value("${jethro.signals.rolling-days:7}") int rollingDays,
                                    @Value("${jethro.signals.sample-limit:500}") int sampleLimit,
                                    @Value("${jethro.signals.cohort-window-seconds:60}") int cohortWindowSeconds) {
        SignalTelemetry.MarkSource marks = instrument -> markFor(tradingCore, instrument);
        // ADR-0082: every call is graded over a geometric ladder of horizons down from the configured
        // base, so which period this desk's edge is real over is measured rather than assumed. One rung
        // reproduces the pre-ADR-0082 single-horizon behaviour exactly.
        return new SignalTelemetry(store, marks, io.jethro.app.fusion.HorizonLadder.rungs(horizonSeconds, horizonRungs),
                flatThresholdBps, rollingDays, sampleLimit, cohortWindowSeconds);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "jethro.signals", name = "enabled", havingValue = "true", matchIfMissing = true)
    SignalTelemetryResolver signalTelemetryResolver(
            SignalTelemetry telemetry,
            @org.springframework.beans.factory.annotation.Qualifier("sharedScheduler") java.util.concurrent.ScheduledExecutorService scheduler,
            @Value("${jethro.signals.resolve-interval-seconds:60}") long intervalSeconds) {
        var resolver = new SignalTelemetryResolver(telemetry, scheduler, intervalSeconds);
        resolver.start();
        return resolver;
    }

    /** Current mark for an instrument from the live cache, empty when unknown/non-positive. */
    private static Optional<BigDecimal> markFor(ObjectProvider<TradingCoreLifecycle> tradingCore, String instrument) {
        TradingCoreLifecycle core = tradingCore.getIfAvailable();
        if (core == null || core.runtime() == null) {
            return Optional.empty();
        }
        var holder = core.runtime().markCache().get(instrument);
        if (holder == null || holder.priceScaled() <= 0) {
            return Optional.empty();
        }
        return Optional.of(Decimals.fromScaledLong(holder.priceScaled(), Decimals.PRICE_SCALE));
    }
}
