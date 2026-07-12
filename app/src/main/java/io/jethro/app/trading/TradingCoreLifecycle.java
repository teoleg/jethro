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
    private volatile ScheduledExecutorService statsLogger;

    public TradingCoreLifecycle(TradingCoreProperties properties) {
        this.properties = properties;
    }

    @Override
    public void start() {
        var adapter = new SimMarketDataAdapter(
                properties.simSeed(),
                properties.simInstruments(),
                Decimals.toScaledLong(properties.simStartPrice(), Decimals.PRICE_SCALE),
                TimeUnit.MILLISECONDS.toNanos(properties.simTickIntervalMillis()));
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
            log.info("trading-core: ticksIn={} dropped={} instruments={} marksFlushed={}",
                    stats.ticksIn(), stats.ticksDropped(), rt.markCache().size(), stats.marksFlushedTotal());
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
