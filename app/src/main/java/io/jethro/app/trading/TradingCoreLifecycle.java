package io.jethro.app.trading;

import io.jethro.domain.Decimals;
import io.jethro.domain.Instrument;
import io.jethro.refdata.RefDataRepository;
import io.jethro.trading.marketdata.FeedStatus;
import io.jethro.trading.marketdata.MarketDataAdapter;
import io.jethro.trading.marketdata.sim.CurveFactorSimulator;
import io.jethro.trading.marketdata.sim.SimMarketDataAdapter;
import io.jethro.trading.marketdata.yahoo.YahooMarketDataAdapter;
import io.jethro.trading.marketdata.yahoo.YahooQuoteClient;
import io.jethro.trading.runtime.LmdbStateStore;
import io.jethro.trading.runtime.TradingCoreRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Starts/stops the trading-core runtime with the application context. */
public final class TradingCoreLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TradingCoreLifecycle.class);

    private final TradingCoreProperties properties;
    private final RefDataRepository refData; // nullable: needed to map yahoo symbols
    private volatile TradingCoreRuntime runtime;
    private volatile MarketDataAdapter adapter;
    private volatile SimMarketDataAdapter simAdapter; // non-null only in sim mode (for regime)
    private volatile ScheduledExecutorService statsLogger;

    public TradingCoreLifecycle(TradingCoreProperties properties, RefDataRepository refData) {
        this.properties = properties;
        this.refData = refData;
    }

    @Override
    public void start() {
        MarketDataAdapter adapter = buildAdapter();
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
        log.info("trading-core started: provider {}, {} instruments, tick/poll config, lmdb at {}, warm-loaded marks {}",
                adapter.name(), properties.simInstruments().size(), properties.lmdbPath(), rt.stats().warmLoadedMarks());
    }

    /** Selects the market-data adapter by configured provider (ADR-0009/0023). Sim is the default;
     *  yahoo is a dev/demo-only real feed, and falls back to sim if it can't be mapped. */
    private MarketDataAdapter buildAdapter() {
        // Curve sim keeps the SOFR curve / linked futures / swaps alive under either provider.
        CurveFactorSimulator curveSim = properties.simCurveOrDefault()
                ? new CurveFactorSimulator(properties.simSeed() + 1, 0.038, 0.009) // 3.8% level, +90bp slope
                : null;

        if ("yahoo".equalsIgnoreCase(properties.providerOrDefault())) {
            Map<String, String> map = yahooSymbolMap();
            if (map.isEmpty()) {
                log.warn("provider=yahoo but no 'yahoo' symbology found (persistence off or unseeded) — "
                        + "falling back to the sim feed");
            } else {
                long spacing = properties.yahooRequestSpacingMillisOrDefault();
                log.warn("MARKET DATA: Yahoo (dev/demo only, ~15-min delayed, unofficial/ToS-limited) — "
                        + "{} instruments, {}ms between requests (~{}s per full refresh). "
                        + "Never a production/real-money feed (ADR-0023).",
                        map.size(), spacing, spacing * map.size() / 1000);
                return new YahooMarketDataAdapter(
                        new YahooQuoteClient(Duration.ofSeconds(10)), map, curveSim, spacing);
            }
        }
        return simAdapter = buildSimAdapter(curveSim);
    }

    private SimMarketDataAdapter buildSimAdapter(CurveFactorSimulator curveSim) {
        long[] startPricesScaled = properties.simInstruments().stream()
                .mapToLong(id -> Decimals.toScaledLong(properties.startPriceFor(id), Decimals.PRICE_SCALE))
                .toArray();
        // Per-tick step calibrated from annualized vol: maxStep(1e-6 of price) =
        // σ_annual · √(Δt / trading-year) · √3 (uniform→σ match).
        double tickSeconds = properties.simTickIntervalMillis() / 1_000.0;
        double tradingYearSeconds = 252 * 6.5 * 3_600;
        long[] maxStepMicros = properties.simInstruments().stream()
                .mapToLong(id -> Math.max(1, Math.round(properties.annualVolFor(id)
                        * Math.sqrt(tickSeconds / tradingYearSeconds) * Math.sqrt(3.0) * 1_000_000)))
                .toArray();
        return new SimMarketDataAdapter(
                properties.simSeed(), properties.simInstruments(), startPricesScaled, maxStepMicros,
                properties.simRegimesOrDefault(), curveSim,
                TimeUnit.MILLISECONDS.toNanos(properties.simTickIntervalMillis()));
    }

    /** instrumentId → Yahoo symbol for the price-quoted names (equity/future/FX); rates stay on
     *  the curve sim. Reads the 'yahoo' symbology seeded in reference data (invariant 2). */
    private Map<String, String> yahooSymbolMap() {
        Map<String, String> map = new LinkedHashMap<>();
        if (refData == null) {
            return map;
        }
        for (Instrument i : refData.findAllInstruments()) {
            String symbol = i.symbology().get("yahoo");
            String assetClass = i.assetClass().name();
            if (symbol != null && ("EQUITY".equals(assetClass) || "FUTURE".equals(assetClass)
                    || "FX".equals(assetClass))) {
                map.put(i.id().value(), symbol);
            }
        }
        return map;
    }

    private void logStats() {
        var rt = runtime;
        if (rt != null) {
            var stats = rt.stats();
            log.info("trading-core: provider={} ticksIn={} dropped={} instruments={} marksFlushed={} regime={}",
                    adapter != null ? adapter.name() : "n/a", stats.ticksIn(), stats.ticksDropped(),
                    rt.markCache().size(), stats.marksFlushedTotal(), regime());
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

    /** Current sim market regime name (CALM when not in sim mode) — for the narrative feed. */
    public String regime() {
        var sim = simAdapter;
        return sim != null ? sim.regime().name() : "CALM";
    }

    /** Market-data feed status for the UI's connection indicator (ADR-0023). */
    public FeedStatus feedStatus() {
        var a = adapter;
        return a != null ? a.status() : new FeedStatus("none", false, 0, 0);
    }
}
