package io.jethro.app.order;

import io.jethro.app.trading.TradingCoreLifecycle;
import io.jethro.trading.riskpnl.InstrumentRef;
import io.jethro.trading.riskpnl.InstrumentRefSource;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Live measured ADV in USD notional from the market path's {@link io.jethro.trading.runtime.VolumeStats}
 * (ADR-0032, phase 1) — replaces the static {@code adv_usd} refdata constant with the actual
 * traded flow the sim is producing, so the execution participation cap and √-impact model
 * (ADR-0025) become data-driven. Falls back (returns empty) on a live feed, before warm-up, or
 * when trading-core isn't up, and the caller then uses the configured ADV — never free execution.
 */
public final class MeasuredAdvSource {

    /** Prints required before the smoothed average is trusted (long half-life is 3,000). */
    private static final long WARMUP_TRADES = 200;

    private final ObjectProvider<TradingCoreLifecycle> tradingCore;
    private final InstrumentRefSource refs; // nullable: multiplier defaults to 1

    public MeasuredAdvSource(ObjectProvider<TradingCoreLifecycle> tradingCore, InstrumentRefSource refs) {
        this.tradingCore = tradingCore;
        this.refs = refs;
    }

    /** Measured ADV (USD), or empty when unavailable (live feed / cold / core down). */
    public Optional<BigDecimal> advUsd(String instrumentId) {
        TradingCoreLifecycle core = tradingCore.getIfAvailable();
        if (core == null) {
            return Optional.empty();
        }
        var stats = core.volumeStats();
        long tradesPerDay = core.simTradesPerDay();
        if (stats == null || tradesPerDay <= 0) {
            return Optional.empty(); // no sim tape (live/replay) — fall back to configured ADV
        }
        if (stats.sampleCount(instrumentId) < WARMUP_TRADES) {
            return Optional.empty();
        }
        double avgNotional = stats.avgTradeNotional(instrumentId); // price×qty units
        if (avgNotional <= 0) {
            return Optional.empty();
        }
        double advUsd = avgNotional * tradesPerDay * multiplier(instrumentId);
        return Optional.of(BigDecimal.valueOf(advUsd).setScale(0, RoundingMode.HALF_UP));
    }

    /** Recent-vs-baseline volume ratio (1.0 when unavailable) — for volume-aware consumers. */
    public double relativeVolume(String instrumentId) {
        TradingCoreLifecycle core = tradingCore.getIfAvailable();
        if (core == null || core.volumeStats() == null) {
            return 1.0;
        }
        return core.volumeStats().relativeVolume(instrumentId);
    }

    private double multiplier(String instrumentId) {
        if (refs == null) {
            return 1.0;
        }
        return refs.find(instrumentId)
                .map(InstrumentRef::multiplier)
                .filter(m -> m != null && m.signum() > 0)
                .map(BigDecimal::doubleValue)
                .orElse(1.0);
    }
}
