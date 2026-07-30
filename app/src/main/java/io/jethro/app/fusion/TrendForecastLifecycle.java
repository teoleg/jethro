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
 * <p>The sensor advances on PRINTS, not on cycles ({@link PrintClock}, ADR-0113): a mark whose provider
 * timestamp has not moved since the last one consumed is skipped, because the mark cache republishes a
 * last-value price whether or not the tape printed and a repeated price is a fabricated zero-return step
 * that decays the EWMAC scale estimator toward zero. The forecaster is confined to this single scheduled
 * thread (it is not thread-safe).
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
    /** ADR-0131: when a still-cold name replays history again — touched only from the tick thread. */
    private final SensorReseed reseed;
    /** ADR-0113: admits a mark only when the market's own clock advanced — same thread as {@link #seeded}. */
    private final PrintClock printClock = new PrintClock();

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
        this.reseed = new SensorReseed(forecaster.warmupSamples());
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
                    continue; // warm-loaded, not yet refreshed by the live feed (invariant 4)
                }
                warmWhileCold(mark.instrumentId(), mark.providerTimestamp());
                if (!printClock.advanced(mark.instrumentId(), mark.providerTimestamp())) {
                    // The tape has not printed since we last looked: the mark cache is republishing the
                    // same last-value price. Advancing here would feed a fabricated zero-return step,
                    // decaying the scale estimator toward zero and booking a telemetry call that resolves
                    // at exactly zero. ADR-0113 — the `stale` flag cannot answer this (it is a warm-load
                    // marker, false forever after the first live tick).
                    continue;
                }
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
     * Replays this instrument's stored recent prices into the forecaster, so a redeploy does not restart
     * its warm-up from zero (ADR-0071). The seed goes through the same {@code update} path as a live mark
     * but is deliberately NOT recorded in the signal telemetry: a historical price is not a call the desk
     * made, and counting it would fabricate track record.
     *
     * <p><b>Runs on first sight and then again, while the name is still cold (ADR-0131).</b> First sight
     * is boot, and a boot that followed an outage, a weekend or a pre-market start reads a store whose
     * series ends in exactly the gap that made the seed necessary — {@link SensorWarmup} then rightly
     * refuses to walk across the hole and hands over a couple of prices against a warm-up of hundreds.
     * Seeding once left the sensor abandoned there for the life of the process. {@link SensorReseed}
     * re-attempts on the sensor's own warm-up cadence until it warms, and never afterwards.
     *
     * <p>The replay goes into a state the caller has just dropped ({@code forget}), because the store already
     * contains every live print the forecaster has consumed and replaying on top would count them twice.
     * That reset is safe precisely because the name is cold: a cold name publishes no view, so nothing
     * downstream is disturbed by re-deriving it.
     *
     * <p>The seed window is anchored on the mark's own <b>provider</b> timestamp, because that is the
     * clock the store is keyed by. Anchoring on wall-clock now empties the seed by exactly the feed's
     * delay (see {@link SensorWarmup} — one clock only).
     */
    private void warmWhileCold(String instrumentId, java.time.Instant providerTimestamp) {
        if (history == null || !reseed.due(instrumentId)) {
            return;
        }
        int needed = forecaster.warmupSamples();
        long anchor = providerTimestamp != null ? providerTimestamp.toEpochMilli() : System.currentTimeMillis();
        forecaster.forget(instrumentId); // replay into a clean state — never on top of consumed prints
        int n = SensorWarmup.warm(history, instrumentId, anchor,
                intervalSeconds * 1_000L, needed,
                price -> forecaster.update(instrumentId, price));
        boolean warm = forecaster.readingFor(instrumentId).warm();
        reseed.record(instrumentId, warm);
        if (warm) {
            log.info("trend sensor warmed {} from {} stored prices (needs {}) — warm", instrumentId, n, needed);
        } else {
            // WARN, not INFO: a sensor that never warms is silent dead code the edge gate can never
            // judge, and that failure has to be loud enough to reach the report (ADR-0071 correction).
            log.warn("trend sensor still cold for {} after seeding {} of {} stored prices — it will not "
                    + "publish until the mark history has accumulated its warm-up span; re-seeding "
                    + "every {} sightings until it does (ADR-0131)", instrumentId, n, needed, needed);
        }
    }

    @Override
    public void close() {
        if (task != null) {
            task.cancel(false); // cancel our task only — the shared pool is owned elsewhere
        }
    }
}
