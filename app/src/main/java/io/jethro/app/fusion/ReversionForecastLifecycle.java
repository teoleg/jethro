package io.jethro.app.fusion;

import io.jethro.app.signal.SignalTelemetry;
import io.jethro.app.trading.TradingCoreLifecycle;
import io.jethro.domain.Side;
import io.jethro.trading.algo.strategy.RangeReversionForecaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Drives the ADR-0070 mean-reversion sensor: on a cadence it feeds every fresh mark to the
 * {@link RangeReversionForecaster} and publishes each name's reading as the {@code reversion} source's
 * current forecast, alongside the existing sources, for the fusion layer to combine. The sibling of
 * {@link TrendForecastLifecycle}, with the same contract and the same limits.
 *
 * <p>It is a <b>forecast source, not an order source</b> — it never touches the order path. Whether any
 * of this becomes a trade is decided downstream by machinery this class cannot influence: the ADR-0064
 * edge gate (does a source's measured expectancy beat measured execution cost?), the ADR-0059 conviction
 * floor, the ADR-0049 backtest-support veto, and the deterministic floor (pre-trade guardrail, firm
 * breaker). Adding a sensor does not weaken any of them; while the gate is reduce-only this source can
 * only ever change how an existing position is worked down, never open one.
 *
 * <p>Each published reading is also recorded as a directional call in the phase-1 signal telemetry, so
 * the reversion source is measured exactly like every other source: its realised expectancy is what
 * decides its fusion weight and whether the edge gate lets it put risk on. A new source arrives with no
 * evidence and earns its allocation, or doesn't — which is the point of adding it as a source rather
 * than as a belief.
 *
 * <p>The sensor advances on PRINTS, not on cycles ({@link PrintClock}, ADR-0113): a mark whose provider
 * timestamp has not moved since the last one consumed is skipped, because the mark cache republishes a
 * last-value price whether or not the tape printed and a repeated price is a fabricated zero-return step
 * — it biases the range and the efficiency ratio toward "no movement" and decays the scale estimator
 * toward zero. The forecaster is confined to this single scheduled thread (it is not thread-safe).
 *
 * <p>On first sight of an instrument the sensor is warmed from the durable recent mark history
 * ({@link SensorWarmup}, ADR-0071). Without it this sensor's warm-up — a full range window plus a scale
 * warm-up, tens of minutes in wall clock — exceeds the process lifetime on a frequently redeployed desk,
 * so it would publish nothing at all and could never accumulate the evidence the edge gate needs to
 * judge it. With no history available this is a no-op and the sensor cold-starts.
 */
public final class ReversionForecastLifecycle implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ReversionForecastLifecycle.class);

    /** The telemetry source name — also the key its measured edge and fusion weight are stored under. */
    public static final String SOURCE = "reversion";

    private final RangeReversionForecaster forecaster;
    private final ForecastRegistry registry;
    private final TradingCoreLifecycle tradingCore;
    private final SignalTelemetry telemetry; // optional observer — null when signal telemetry is off
    private final SensorWarmup.History history; // optional — null means cold-start (ADR-0071)
    private final ScheduledExecutorService scheduler;
    private final long intervalSeconds;
    /** Instruments already warmed from history — touched only from the scheduled tick thread. */
    private final java.util.Set<String> seeded = new java.util.HashSet<>();
    /** ADR-0113: admits a mark only when the market's own clock advanced — same thread as {@link #seeded}. */
    private final PrintClock printClock = new PrintClock();

    private Future<?> task;

    public ReversionForecastLifecycle(RangeReversionForecaster forecaster, ForecastRegistry registry,
                                      TradingCoreLifecycle tradingCore, SignalTelemetry telemetry,
                                      SensorWarmup.History history,
                                      ScheduledExecutorService scheduler, long intervalSeconds) {
        this.forecaster = forecaster;
        this.registry = registry;
        this.tradingCore = tradingCore;
        this.telemetry = telemetry;
        this.history = history;
        this.scheduler = scheduler;
        this.intervalSeconds = Math.max(1, intervalSeconds);
    }

    public void start() {
        task = scheduler.scheduleWithFixedDelay(this::tick, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("reversion sensor started (range position, every {}s) — ADR-0070 forecast source, "
                + "places no orders", intervalSeconds);
    }

    /** The forecaster's current per-instrument readings (for the API/UI and tests). */
    public RangeReversionForecaster forecaster() {
        return forecaster;
    }

    private void tick() {
        try {
            var runtime = tradingCore == null ? null : tradingCore.runtime();
            if (runtime == null) {
                return;
            }
            for (var mark : runtime.markCache().snapshot()) {
                if (mark.stale()) {
                    continue; // warm-loaded, not yet refreshed by the live feed (invariant 4)
                }
                warmIfFirstSight(mark.instrumentId(), mark.providerTimestamp());
                if (!printClock.advanced(mark.instrumentId(), mark.providerTimestamp())) {
                    // The tape has not printed since we last looked: the mark cache is republishing the
                    // same last-value price. Advancing here would feed a fabricated zero-return step,
                    // decaying the scale estimator toward zero and booking a telemetry call that resolves
                    // at exactly zero. ADR-0113 — the `stale` flag cannot answer this (it is a warm-load
                    // marker, false forever after the first live tick).
                    continue;
                }
                var reading = forecaster.update(mark.instrumentId(), mark.price());
                registry.submitReversion(mark.instrumentId(), reading.score());
                if (telemetry != null && reading.score() != 0.0) {
                    telemetry.record(SOURCE, mark.instrumentId(),
                            reading.score() > 0 ? Side.BUY : Side.SELL, mark.price());
                }
            }
        } catch (Exception e) {
            log.debug("reversion sensor tick failed: {}", e.toString());
        }
    }

    /**
     * Replays this instrument's stored recent prices into the forecaster the first time we see it, so a
     * redeploy does not restart its warm-up from zero (ADR-0071). The seed goes through the same
     * {@code update} path as a live mark but is deliberately NOT recorded in the signal telemetry: a
     * historical price is not a call the desk made, and counting it would fabricate track record.
     *
     * <p>The seed window is anchored on the mark's own <b>provider</b> timestamp, because that is the
     * clock the store is keyed by. Anchoring on wall-clock now empties the seed by exactly the feed's
     * delay (see {@link SensorWarmup} — one clock only).
     */
    private void warmIfFirstSight(String instrumentId, java.time.Instant providerTimestamp) {
        if (history == null || !seeded.add(instrumentId)) {
            return;
        }
        int needed = forecaster.warmupSamples();
        long anchor = providerTimestamp != null ? providerTimestamp.toEpochMilli() : System.currentTimeMillis();
        var seed = SensorWarmup.warm(history, instrumentId, anchor,
                intervalSeconds * 1_000L, needed,
                price -> forecaster.update(instrumentId, price));
        boolean warm = forecaster.readingFor(instrumentId).warm();
        if (warm) {
            log.info("reversion sensor warmed {} from {} stored prices (needs {}) — warm", instrumentId,
                    seed.size(), needed);
        } else {
            // WARN, not INFO: a sensor that never warms is silent dead code the edge gate can never
            // judge, and that failure has to be loud enough to reach the report (ADR-0071 correction).
            // The terminator and span say WHY it is short — window or series end (ADR-0138).
            log.warn("reversion sensor still cold for {} after seeding {} of {} stored prices — stopped on"
                    + " {} covering {}s in {} read(s) at a {}ms step; it will not publish until the mark "
                    + "history has accumulated its warm-up span", instrumentId, seed.size(), needed,
                    seed.termination(), seed.spanMillis() / 1000L, seed.reads(), seed.stepMillis());
        }
    }

    @Override
    public void close() {
        if (task != null) {
            task.cancel(false); // cancel our task only — the shared pool is owned elsewhere
        }
    }
}
