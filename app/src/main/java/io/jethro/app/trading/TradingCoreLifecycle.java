package io.jethro.app.trading;

import io.jethro.domain.Decimals;
import io.jethro.trading.marketdata.sim.SimMarketDataAdapter;
import io.jethro.trading.runtime.LmdbStateStore;
import io.jethro.trading.runtime.TradingCoreRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Starts/stops the trading-core runtime with the application context. */
public final class TradingCoreLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TradingCoreLifecycle.class);

    private final TradingCoreProperties properties;
    private volatile TradingCoreRuntime runtime;
    private volatile SimMarketDataAdapter adapter;
    private volatile ScheduledExecutorService statsLogger;

    public TradingCoreLifecycle(TradingCoreProperties properties) {
        this.properties = properties;
    }

    @Override
    public void start() {
        long[] startPricesScaled = properties.simInstruments().stream()
                .mapToLong(id -> Decimals.toScaledLong(properties.startPriceFor(id), Decimals.PRICE_SCALE))
                .toArray();
        // Per-tick step calibrated from annualized vol: maxStep(1e-6 of price) =
        // σ_annual · √(Δt / trading-year) · √3 (uniform→σ match). Worked example: 25% vol,
        // 100ms tick, year = 252d·6.5h ≈ 5.9e6s → 0.25·√(0.1/5.9e6)·1.732·1e6 ≈ 56 →
        // a typical 5-minute move of ~0.18%, instead of the old multi-percent jumps.
        double tickSeconds = properties.simTickIntervalMillis() / 1_000.0;
        double tradingYearSeconds = 252 * 6.5 * 3_600;
        long[] maxStepMicros = properties.simInstruments().stream()
                .mapToLong(id -> Math.max(1, Math.round(properties.annualVolFor(id)
                        * Math.sqrt(tickSeconds / tradingYearSeconds) * Math.sqrt(3.0) * 1_000_000)))
                .toArray();
        var adapter = new SimMarketDataAdapter(
                properties.simSeed(),
                properties.simInstruments(),
                startPricesScaled,
                maxStepMicros,
                properties.simRegimesOrDefault(),
                TimeUnit.MILLISECONDS.toNanos(properties.simTickIntervalMillis()));
        this.adapter = adapter;
        var store = LmdbStateStore.open(
                Path.of(properties.lmdbPath()),
                properties.lmdbMaxSizeMb() * 1024 * 1024);
        var rt = new TradingCoreRuntime(adapter, properties.bufferCapacity(), store);
        rt.start();
        runtime = rt;

        statsLogger = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "trading-core-stats");
            t.setDaemon(true);
            return t;
        });
        statsLogger.scheduleAtFixedRate(this::logStats, 10, 10, TimeUnit.SECONDS);
        log.info("trading-core started: {} instruments, sim seed {}, tick interval {}ms, lmdb at {}, warm-loaded marks {}",
                properties.simInstruments().size(), properties.simSeed(),
                properties.simTickIntervalMillis(), properties.lmdbPath(), rt.stats().warmLoadedMarks());
    }

    private void logStats() {
        var rt = runtime;
        if (rt != null) {
            var stats = rt.stats();
            var sim = adapter;
            log.info("trading-core: ticksIn={} dropped={} instruments={} marksFlushed={} regime={}",
                    stats.ticksIn(), stats.ticksDropped(), rt.markCache().size(), stats.marksFlushedTotal(),
                    sim != null ? sim.regime() : "n/a");
        }
    }

    @Override
    public void stop() {
        var logger = statsLogger;
        if (logger != null) {
            logger.shutdownNow();
        }
        var rt = runtime;
        if (rt != null) {
            logStats();
            rt.close();
            runtime = null;
            log.info("trading-core stopped");
        }
    }

    @Override
    public boolean isRunning() {
        return runtime != null;
    }

    public TradingCoreRuntime runtime() {
        return runtime;
    }
}
