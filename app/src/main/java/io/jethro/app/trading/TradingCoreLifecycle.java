package io.jethro.app.trading;

import io.jethro.domain.Decimals;
import io.jethro.domain.Instrument;
import io.jethro.refdata.RefDataRepository;
import io.jethro.trading.marketdata.FeedStatus;
import io.jethro.trading.marketdata.MarketDataAdapter;
import io.jethro.trading.marketdata.finnhub.FinnhubMarketDataAdapter;
import io.jethro.trading.marketdata.sim.CorrelatedMarketDataAdapter;
import io.jethro.trading.marketdata.sim.CurveFactorSimulator;
import io.jethro.trading.marketdata.sim.CurveMarkSource;
import io.jethro.trading.marketdata.sim.FactorModelConfig;
import io.jethro.trading.marketdata.sim.Quotes;
import io.jethro.trading.marketdata.sim.RealTreasuryCurve;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Starts/stops the trading-core runtime with the application context. */
public final class TradingCoreLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TradingCoreLifecycle.class);

    private final TradingCoreProperties properties;
    private final RefDataRepository refData; // nullable: needed to map yahoo symbols
    private final FinnhubRateLimiter rateLimiter; // shared Finnhub REST budget (news + curve)
    private final io.jethro.app.order.ExecutionProperties executionCosts; // sim quote spreads (ADR-0025)
    private final io.jethro.trading.runtime.MarkCache.QuarantineStore quarantineStore; // durable freeze (GAP-2)
    private volatile TradingCoreRuntime runtime;
    private volatile MarketDataAdapter adapter;
    private volatile java.util.function.Supplier<String> regimeSource; // non-null only in sim mode
    private volatile java.util.function.LongSupplier simDayIndexSource; // correlated sim only
    private volatile io.jethro.trading.marketdata.sim.SimControl simControl; // ADR-0031: correlated sim only
    private volatile RealTreasuryCurve realCurve;     // non-null only when the live curve is active
    private volatile TreasuryCurveFetcher curveFetcher;
    private volatile String curveSource = "sim";      // "treasury-live" or "sim" (for the UI)
    private volatile ScheduledExecutorService statsLogger;
    private volatile ScheduledExecutorService curveRefresher;

    public TradingCoreLifecycle(TradingCoreProperties properties, RefDataRepository refData,
                                FinnhubRateLimiter rateLimiter) {
        this(properties, refData, rateLimiter, new io.jethro.app.order.ExecutionProperties(null, null, null));
    }

    public TradingCoreLifecycle(TradingCoreProperties properties, RefDataRepository refData,
                                FinnhubRateLimiter rateLimiter,
                                io.jethro.app.order.ExecutionProperties executionCosts) {
        this(properties, refData, rateLimiter, executionCosts,
                io.jethro.trading.runtime.MarkCache.QuarantineStore.NONE);
    }

    public TradingCoreLifecycle(TradingCoreProperties properties, RefDataRepository refData,
                                FinnhubRateLimiter rateLimiter,
                                io.jethro.app.order.ExecutionProperties executionCosts,
                                io.jethro.trading.runtime.MarkCache.QuarantineStore quarantineStore) {
        this.properties = properties;
        this.refData = refData;
        this.rateLimiter = rateLimiter;
        this.executionCosts = executionCosts;
        this.quarantineStore = quarantineStore != null
                ? quarantineStore : io.jethro.trading.runtime.MarkCache.QuarantineStore.NONE;
    }

    @Override
    public void start() {
        MarketDataAdapter adapter = buildAdapter();
        this.adapter = adapter;
        var store = LmdbStateStore.open(
                Path.of(properties.lmdbPath()),
                properties.lmdbMaxSizeMb() * 1024 * 1024);
        var rt = new TradingCoreRuntime(adapter, properties.bufferCapacity(), store,
                jumpThresholds(), quarantineStore);
        rt.start();
        runtime = rt;

        statsLogger = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "trading-core-stats");
            t.setDaemon(true);
            return t;
        });
        statsLogger.scheduleAtFixedRate(this::logStats, 10, 10, TimeUnit.SECONDS);

        // Refresh the live Treasury curve on a slow cadence (curves move slowly; keeps REST
        // calls low and inside the shared Finnhub budget). Only when the live curve is active.
        if (realCurve != null && curveFetcher != null) {
            long refreshSeconds = properties.treasuryCurveRefreshSecondsOrDefault();
            curveRefresher = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "treasury-curve-refresh");
                t.setDaemon(true);
                return t;
            });
            curveRefresher.scheduleWithFixedDelay(this::refreshCurve, refreshSeconds, refreshSeconds, TimeUnit.SECONDS);
        }
        log.info("trading-core started: provider {}, curve {}, {} instruments, tick/poll config, lmdb at {}, warm-loaded marks {}",
                adapter.name(), curveSource, properties.simInstruments().size(), properties.lmdbPath(), rt.stats().warmLoadedMarks());
    }

    /** Selects the market-data adapter by configured provider (ADR-0009/0023). Sim is the default;
     *  yahoo is a dev/demo-only real feed, and falls back to sim if it can't be mapped. */
    private MarketDataAdapter buildAdapter() {
        // The curve source keeps the SOFR/Treasury curve, linked futures and swaps alive under any
        // provider — a live Treasury curve when configured (ADR-0024), else the factor sim.
        CurveMarkSource curveSim = buildCurveSource();

        String provider = properties.providerOrDefault();

        if ("finnhub".equalsIgnoreCase(provider)) {
            MarketDataAdapter finnhub = buildFinnhubAdapter(curveSim);
            if (finnhub != null) {
                return finnhub;
            }
            // token missing or nothing mapped — buildFinnhubAdapter logged why; fall through to sim.
        }

        if ("yahoo".equalsIgnoreCase(provider)) {
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
        return buildSimAdapter(curveSim, properties.simInstruments());
    }

    /** The curve source: a live US Treasury curve via Finnhub when enabled and a token is set
     *  (probed once at startup — a failed/gated probe falls back to the sim, logged), else the
     *  seedable SOFR factor sim. Null when the curve is disabled entirely. */
    private CurveMarkSource buildCurveSource() {
        if (!properties.simCurveOrDefault()) {
            return null; // curve disabled — no rates marks at all
        }
        if (properties.realCurveOrDefault()) {
            // Source chain, most-preferred first: Finnhub only when a token exists (its bond
            // endpoints are premium-gated on free keys — expected to fail there), then the
            // OFFICIAL treasury.gov daily par-yield feed (free, keyless). First probe that
            // returns data wins and stays the refresh source; all fail → the sim curve, logged.
            List<TreasuryCurveFetcher> chain = new ArrayList<>();
            if (!properties.finnhubTokenOrEmpty().isEmpty()) {
                chain.add(new FinnhubYieldCurveClient(
                        properties.finnhubTokenOrEmpty(), Duration.ofSeconds(10), rateLimiter));
            }
            chain.add(new TreasuryDirectYieldCurveClient(Duration.ofSeconds(15)));
            for (TreasuryCurveFetcher fetcher : chain) {
                double[] probe = fetcher.fetchNodeZeros();
                if (probe != null) {
                    RealTreasuryCurve curve = new RealTreasuryCurve(probe);
                    this.realCurve = curve;
                    this.curveFetcher = fetcher;
                    this.curveSource = "treasury-live";
                    log.warn("RATES CURVE: LIVE US Treasury curve via {} — {} (refresh {}s). "
                                    + "DV01, swap PV and rate scenarios reprice on real levels (ADR-0024).",
                            fetcher.source(), describeCurve(probe),
                            properties.treasuryCurveRefreshSecondsOrDefault());
                    return curve;
                }
                log.warn("RATES CURVE: {} returned no data — trying the next source", fetcher.source());
            }
            log.warn("RATES CURVE: no live source reachable — using the SOFR factor sim curve. "
                    + "(treasury.gov needs outbound internet; check connectivity.)");
        }
        this.curveSource = "sim";
        return new CurveFactorSimulator(properties.simSeed() + 1, 0.038, 0.009); // 3.8% level, +90bp slope
    }

    private static String describeCurve(double[] zeros) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < CurveMarkSource.TENORS.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append((int) CurveMarkSource.TENORS[i]).append("Y=")
                    .append(String.format(java.util.Locale.ROOT, "%.2f%%", zeros[i] * 100));
        }
        return sb.toString();
    }

    /** Refreshes the live curve from the provider; keeps the last good curve on any failure. */
    private void refreshCurve() {
        var fetcher = curveFetcher;
        var curve = realCurve;
        if (fetcher == null || curve == null) {
            return;
        }
        double[] zeros = fetcher.fetchNodeZeros();
        if (zeros != null) {
            curve.update(zeros);
        }
    }

    /** Active rates-curve source for the UI: "treasury-live" (real) or "sim" (factor curve). */
    public String curveSource() {
        return curveSource;
    }

    /** Finnhub real-time equities (WebSocket) composed with a background feed for everything
     *  Finnhub's free tier doesn't stream: Yahoo (delayed) for the futures/FX it covers, else
     *  sim; the SOFR curve rides the background either way. Null if no token/symbology — caller
     *  falls back to sim. */
    private MarketDataAdapter buildFinnhubAdapter(CurveMarkSource curveSim) {
        String token = properties.finnhubTokenOrEmpty();
        Map<String, String> covered = finnhubSymbolMap();
        if (token.isEmpty()) {
            log.warn("provider=finnhub but jethro.trading.finnhub-token is blank — falling back to the sim feed. "
                    + "Get a free key at finnhub.io and set FINNHUB=... (ADR-0024).");
            return null;
        }
        if (covered.isEmpty()) {
            log.warn("provider=finnhub but no 'finnhub' symbology found — falling back to the sim feed");
            return null;
        }
        // Background for what Finnhub doesn't stream. Prefer Yahoo (real, delayed) for the
        // price-quoted rest (futures/FX/SAP), else sim; the curve rides the background either way.
        Map<String, String> yahooRest = yahooSymbolMap();
        covered.keySet().forEach(yahooRest::remove); // equities are on Finnhub now
        MarketDataAdapter background;
        if (!yahooRest.isEmpty()) {
            long spacing = properties.yahooRequestSpacingMillisOrDefault();
            background = new YahooMarketDataAdapter(
                    new YahooQuoteClient(Duration.ofSeconds(10)), yahooRest, curveSim, spacing);
            log.warn("MARKET DATA: Finnhub real-time WS for {} equities + Yahoo (delayed) for {} others "
                    + "+ sim curve. Dev/demo only, never production/real-money (ADR-0024).",
                    covered.size(), yahooRest.size());
        } else {
            List<String> uncovered = properties.simInstruments().stream()
                    .filter(id -> !covered.containsKey(id)).toList();
            background = buildSimAdapter(curveSim, uncovered);
            log.warn("MARKET DATA: Finnhub real-time WS for {} equities + sim for {} others. "
                    + "Dev/demo only (ADR-0024).", covered.size(), uncovered.size());
        }
        return new FinnhubMarketDataAdapter(token, covered, background);
    }

    /** The sim feed: the correlated cross-asset factor engine (ADR-0026) by default —
     *  one joint draw per tick moves equities, FX and the curve in concert — or the legacy
     *  independent-walk engine when {@code sim-engine=legacy} or the calibration won't load. */
    private MarketDataAdapter buildSimAdapter(CurveMarkSource curveSim, List<String> instruments) {
        if (instruments.isEmpty()) {
            // Nothing left for the sim (all covered by the real feed): a 1-instrument idle sim keeps
            // the curve alive if configured; otherwise the market path just carries the real feed.
            instruments = properties.simInstruments().subList(0, 1);
        }
        List<String> ids = instruments;
        long[] startPricesScaled = ids.stream()
                .mapToLong(id -> Decimals.toScaledLong(properties.startPriceFor(id), Decimals.PRICE_SCALE))
                .toArray();
        if (properties.correlatedSimOrDefault()) {
            try {
                FactorModelConfig calibration = SimCalibrationLoader.load(properties.simCalibrationPathOrNull());
                var sim = new CorrelatedMarketDataAdapter(
                        properties.simSeed(), calibration, ids, startPricesScaled, curveSim,
                        TimeUnit.MILLISECONDS.toNanos(properties.simTickIntervalMillis()),
                        properties.simSecondsPerDayOrDefault(), quoteSpecSource());
                this.regimeSource = () -> sim.regime().name();
                this.simDayIndexSource = sim::simDayIndex;
                this.simControl = sim.control();
                log.info("SIM ENGINE: correlated factor model (ADR-0026) — {} instruments, {} regimes, "
                                + "t(ν={}) tails, {}s per simulated trading day",
                        calibration.instruments().size(), calibration.regimes().size(),
                        (int) calibration.tDegreesOfFreedom(), properties.simSecondsPerDayOrDefault());
                return sim;
            } catch (Exception e) {
                log.error("sim calibration failed to load — falling back to the LEGACY independent-walk "
                        + "engine (uncorrelated!): {}", e.toString());
            }
        }
        // Legacy engine: per-tick step calibrated from annualized vol: maxStep(1e-6 of price) =
        // σ_annual · √(Δt / trading-year) · √3 (uniform→σ match).
        double tickSeconds = properties.simTickIntervalMillis() / 1_000.0;
        double tradingYearSeconds = 252 * 6.5 * 3_600;
        long[] maxStepMicros = ids.stream()
                .mapToLong(id -> Math.max(1, Math.round(properties.annualVolFor(id)
                        * Math.sqrt(tickSeconds / tradingYearSeconds) * Math.sqrt(3.0) * 1_000_000)))
                .toArray();
        var sim = new SimMarketDataAdapter(
                properties.simSeed(), ids, startPricesScaled, maxStepMicros,
                properties.simRegimesOrDefault(), curveSim,
                TimeUnit.MILLISECONDS.toNanos(properties.simTickIntervalMillis()));
        this.regimeSource = () -> sim.regime().name();
        return sim;
    }

    /**
     * The corporate-action / bad-print jump guard's per-instrument thresholds (bps of the
     * previous mark), from the instrument's asset class via reference data — configured under
     * {@code jethro.trading.mark-jump-bps.<CLASS>}. Instruments outside the master (curve
     * pseudo-quotes) get the default. Thresholds sit far above any legitimate one-tick move
     * (incl. the sim's shock/overnight gaps) and far below a split (2:1 = −50%).
     */
    private io.jethro.trading.runtime.MarkCache.JumpThresholds jumpThresholds() {
        Map<String, Integer> byInstrument = new LinkedHashMap<>();
        if (refData != null) {
            for (Instrument i : refData.findAllInstruments()) {
                byInstrument.put(i.id().value(), properties.markJumpBpsFor(i.assetClass().name()));
            }
        }
        int fallback = properties.markJumpBpsFor(null);
        return id -> byInstrument.getOrDefault(id, fallback);
    }

    /**
     * Per-instrument quote synthesis for the sim feed (ADR-0025): bid/ask around the mid at
     * the SAME per-class spreads the execution cost model charges — the quoted touch and the
     * synthetic touch agree by construction. Swaps are rate-quoted (additive bp). Instruments
     * outside the master (curve pseudo-quotes) get no quotes.
     */
    private Quotes.QuoteSpecSource quoteSpecSource() {
        Map<String, Quotes.QuoteSpec> byId = new LinkedHashMap<>();
        if (refData != null) {
            Map<String, Map<String, String>> attributes = refData.findAllAttributes();
            for (Instrument i : refData.findAllInstruments()) {
                String assetClass = i.assetClass().name();
                // Per-NAME spread (V25) first — the SAME number the execution model charges —
                // else the class config: quoted touch and charged touch agree by construction.
                String perName = attributes.getOrDefault(i.id().value(), Map.of()).get("spread_bps");
                java.math.BigDecimal spread = perName != null
                        ? new java.math.BigDecimal(perName) : executionCosts.spreadFor(assetClass);
                int centiBps = spread
                        .movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).intValueExact();
                byId.put(i.id().value(), new Quotes.QuoteSpec(centiBps, "SWAP".equals(assetClass)));
            }
        }
        return byId::get;
    }

    /** The correlated sim's tick-day counter (null on live feeds / legacy sim) — the
     *  session calendar keys to the TAPE's days, not wall time (ADR-0027). */
    public java.util.function.LongSupplier simDayIndexSource() {
        return simDayIndexSource;
    }

    /** The live sim control panel (ADR-0031), or null when the feed isn't the correlated sim
     *  (live/replay/legacy). The REST surface additionally gates on {@code feedMode == SIM}. */
    public io.jethro.trading.marketdata.sim.SimControl simControl() {
        return simControl;
    }

    /** instrumentId → Finnhub symbol for the covered equities (US listings). Reads 'finnhub'
     *  symbology (invariant 2). */
    private Map<String, String> finnhubSymbolMap() {
        Map<String, String> map = new LinkedHashMap<>();
        if (refData == null) {
            return map;
        }
        for (Instrument i : refData.findAllInstruments()) {
            String symbol = i.symbology().get("finnhub");
            if (symbol != null) {
                map.put(i.id().value(), symbol);
            }
        }
        return map;
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
        var curveRt = curveRefresher;
        if (curveRt != null) {
            curveRt.shutdownNow();
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
        var source = regimeSource;
        return source != null ? source.get() : "CALM";
    }

    /** Market-data feed status(es) for the UI's connection indicators — one per source, so a
     *  composite feed (Finnhub + Yahoo, ADR-0024) shows a pill each. */
    public List<FeedStatus> feedStatuses() {
        var a = adapter;
        return a != null ? a.statuses() : List.of(new FeedStatus("none", false, 0, 0));
    }
}
