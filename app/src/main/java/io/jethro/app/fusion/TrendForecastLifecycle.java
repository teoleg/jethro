package io.jethro.app.fusion;

import io.jethro.app.signal.SignalTelemetry;
import io.jethro.app.trading.TradingCoreLifecycle;
import io.jethro.domain.Side;
import io.jethro.trading.algo.strategy.EwmacTrendForecaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Drives the ADR-0066 trend sensor: on a cadence it feeds every fresh mark to the
 * {@link EwmacTrendForecaster} and publishes each name's reading as the {@code trend} source's current
 * forecast, alongside the existing sources, for the fusion layer to combine.
 *
 * <p>It is a <b>forecast source, not an order source</b> — it never touches the order path. Whether any
 * of this becomes a trade is decided downstream by machinery this class cannot influence: the ADR-0064
 * edge gate (does a source's measured expectancy beat measured execution cost?), the ADR-0059 conviction
 * floor, the ADR-0049 backtest-support veto, and the deterministic floor (pre-trade guardrail, firm
 * breaker). Adding a sensor does not weaken any of them.
 *
 * <p>Each published reading is also recorded as a directional call in the phase-1 signal telemetry, so
 * the trend source is measured exactly like every other source: its realised expectancy is what decides
 * its fusion weight and whether the edge gate lets it put risk on. A new source arrives with no
 * evidence and earns its allocation, or doesn't.
 *
 * <p>Stale marks are skipped — a repeated stale price would feed the sensor a fabricated zero-return
 * step. The forecaster is confined to this single scheduled thread (it is not thread-safe).
 *
 * <p>On first sight of an instrument the sensor is warmed from the durable recent mark history
 * ({@link SensorWarmup}, ADR-0071) so it boots calibrated instead of spending its whole warm-up silent
 * after every redeploy. With no history available this is a no-op and the sensor cold-starts exactly as
 * before.
 */
public final class TrendForecastLifecycle implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TrendForecastLifecycle.class);

    /** The telemetry source name — also the key its measured edge and fusion weight are stored under. */
    public static final String SOURCE = "trend";

    private final EwmacTrendForecaster forecaster;
    private final ForecastRegistry registry;
    private final TradingCoreLifecycle tradingCore;
    private final SignalTelemetry telemetry; // optional observer — null when signal telemetry is off
    private final SensorWarmup.History history; // optional — null means cold-start (ADR-0071)
    private final ScheduledExecutorService scheduler;
    private final long intervalSeconds;
    /** Instruments already warmed from history — touched only from the scheduled tick thread. */
    private final java.util.Set<String> seeded = new java.util.HashSet<>();

    private Future<?> task;

    public TrendForecastLifecycle(EwmacTrendForecaster forecaster, ForecastRegistry registry,
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
        log.info("trend sensor started (EWMAC, every {}s) — ADR-0066 forecast source, places no orders",
                intervalSeconds);
    }

    /** The forecaster's current per-instrument readings (for the API/UI and tests). */
    public EwmacTrendForecaster forecaster() {
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
                registry.submitTrend(mark.instrumentId(), reading.score());
                if (telemetry != null && reading.score() != 0.0) {
                    telemetry.record(SOURCE, mark.instrumentId(),
                            reading.score() > 0 ? Side.BUY : Side.SELL, mark.price());
                }
            }
        } catch (Exception e) {
            log.debug("trend sensor tick failed: {}", e.toString());
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
            log.info("trend sensor warmed {} from {} stored prices (needs {}) — {}",
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
