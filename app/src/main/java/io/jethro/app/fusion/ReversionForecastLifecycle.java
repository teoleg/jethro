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
 * <p>Stale marks are skipped — a repeated stale price would feed the sensor a fabricated zero-return
 * step, biasing both the range and the efficiency ratio toward "no movement". The forecaster is confined
 * to this single scheduled thread (it is not thread-safe).
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
                    continue; // never advance the sensor's windows on a repeated stale price
                }
                warmIfFirstSight(mark.instrumentId());
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
     */
    private void warmIfFirstSight(String instrumentId) {
        if (history == null || !seeded.add(instrumentId)) {
            return;
        }
        int n = SensorWarmup.warm(history, instrumentId, System.currentTimeMillis(),
                intervalSeconds * 1_000L, forecaster.warmupSamples(),
                price -> forecaster.update(instrumentId, price));
        if (n > 0) {
            log.info("reversion sensor warmed {} from {} stored prices (needs {}) — {}",
                    instrumentId, n, forecaster.warmupSamples(),
                    forecaster.readingFor(instrumentId).warm() ? "warm" : "still warming");
        }
    }

    @Override
    public void close() {
        if (task != null) {
            task.cancel(false); // cancel our task only — the shared pool is owned elsewhere
        }
    }
}
