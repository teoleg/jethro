package io.jethro.app.fusion;

import io.jethro.app.signal.SignalTelemetry;
import io.jethro.app.trading.TradingCoreLifecycle;
import io.jethro.domain.Side;
import io.jethro.trading.riskpnl.InstrumentRefSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Drives the ADR-0121 cross-sectional residual reversion sensor: on a cadence it feeds every fresh
 * print into {@link CrossSectionalReversionForecaster}, computes the whole cross-section in one sweep,
 * and publishes each name's residual reading as the {@code xsreversion} source's current forecast. The
 * sibling of {@link TrendForecastLifecycle} and {@link ReversionForecastLifecycle}, under exactly the
 * same contract and the same limits.
 *
 * <p>It is a <b>forecast source, not an order source</b> — it never touches the order path. Whether any
 * of this becomes a trade is decided downstream by machinery this class cannot influence: the ADR-0064
 * edge gate (does a source's measured expectancy beat measured execution cost, at the confidence the
 * desk demands?), the ADR-0059 conviction floor, the ADR-0049 backtest-support veto, and the
 * deterministic floor (pre-trade guardrail, firm breaker). Adding a sensor does not weaken any of them;
 * while the gate is reduce-only this source can only ever change how an existing position is worked
 * down, never open one.
 *
 * <p>Each published reading is also recorded as a directional call in the phase-1 signal telemetry, so
 * this source is measured exactly like every other one: its realised expectancy is what decides its
 * fusion weight (ADR-0067/0097/0111) and whether the edge gate ever lets it put risk on. A new source
 * arrives with no evidence and earns its allocation, or it doesn't — which is the entire point of
 * adding it as a measured source rather than as a belief.
 *
 * <p><b>One sweep, one cohort.</b> Unlike the per-name sensors this one is inherently cross-sectional:
 * it cannot form a view on a name without the peer group's returns, so it publishes the whole
 * cross-section at once. That is exactly the emission shape ADR-0120 defines a cohort as, so the edge
 * gate's standard error and degrees of freedom count this source correctly with no special case.
 *
 * <p><b>One clock — the feed's.</b> Prints are stamped and windowed in PROVIDER time, and the sweep's
 * "now" is the newest provider timestamp seen this pass, never {@code System.currentTimeMillis()}. On a
 * delayed, replayed or simulated feed a wall-clock window on a provider-stamped series empties itself by
 * exactly the feed's lag (the ADR-0071 lesson, paid for once already).
 *
 * <p>On first sight of an instrument the window is warmed from the durable recent mark history
 * ({@link SensorWarmup.History}, ADR-0071). This matters more here than for the per-name sensors: this
 * desk redeploys on a half-hour cadence, so a cold sensor with a quarter-hour window would spend half
 * of every process life unable to speak and could never accumulate the evidence the edge gate needs to
 * judge it. With no history available this is a no-op and the sensor cold-starts.
 */
public final class CrossSectionalReversionLifecycle implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CrossSectionalReversionLifecycle.class);

    /** The telemetry source name — also the key its measured edge and fusion weight are stored under. */
    public static final String SOURCE = "xsreversion";

    private final CrossSectionalReversionForecaster forecaster;
    private final ForecastRegistry registry;
    private final TradingCoreLifecycle tradingCore;
    private final InstrumentRefSource refs; // peer group = asset class from the instrument master
    private final SignalTelemetry telemetry; // optional observer — null when signal telemetry is off
    private final SensorWarmup.History history; // optional — null means cold-start (ADR-0071)
    private final ScheduledExecutorService scheduler;
    private final long intervalSeconds;
    /** Instruments already warmed from history — touched only from the scheduled tick thread. */
    private final java.util.Set<String> seeded = new java.util.HashSet<>();
    /** ADR-0113: admits a mark only when the market's own clock advanced — same thread as {@link #seeded}. */
    private final PrintClock printClock = new PrintClock();

    private volatile Map<String, CrossSectionalReversionForecaster.Reading> lastSweep = Map.of();
    private Future<?> task;

    public CrossSectionalReversionLifecycle(CrossSectionalReversionForecaster forecaster,
                                            ForecastRegistry registry, TradingCoreLifecycle tradingCore,
                                            InstrumentRefSource refs, SignalTelemetry telemetry,
                                            SensorWarmup.History history,
                                            ScheduledExecutorService scheduler, long intervalSeconds) {
        this.forecaster = forecaster;
        this.registry = registry;
        this.tradingCore = tradingCore;
        this.refs = refs;
        this.telemetry = telemetry;
        this.history = history;
        this.scheduler = scheduler;
        this.intervalSeconds = Math.max(1, intervalSeconds);
    }

    public void start() {
        task = scheduler.scheduleWithFixedDelay(this::tick, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("cross-sectional reversion sensor started (residual vs peer group, every {}s) — "
                + "ADR-0121 forecast source, places no orders", intervalSeconds);
    }

    /** The most recent sweep's readings (for the API/UI and tests) — a view, never a size. */
    public Map<String, CrossSectionalReversionForecaster.Reading> lastSweep() {
        return lastSweep;
    }

    private void tick() {
        try {
            var runtime = tradingCore == null ? null : tradingCore.runtime();
            if (runtime == null) {
                return;
            }
            Map<String, BigDecimal> pricesSeen = new LinkedHashMap<>();
            long marketNow = Long.MIN_VALUE;
            for (var mark : runtime.markCache().snapshot()) {
                if (mark.stale() || mark.providerTimestamp() == null) {
                    continue; // warm-loaded, not yet refreshed by the live feed (invariant 4)
                }
                String peerGroup = peerGroupFor(mark.instrumentId());
                if (peerGroup == null) {
                    continue; // no master row (curve pseudo-instruments) — not a peer of anything
                }
                long atMillis = mark.providerTimestamp().toEpochMilli();
                marketNow = Math.max(marketNow, atMillis);
                pricesSeen.put(mark.instrumentId(), mark.price());
                warmIfFirstSight(mark.instrumentId(), peerGroup, atMillis);
                if (!printClock.advanced(mark.instrumentId(), mark.providerTimestamp())) {
                    // The tape has not printed since we last looked: the mark cache is republishing the
                    // same last-value price. Recording it would let a name that has stopped printing keep
                    // claiming a current reading, and would shrink its observed span toward zero — the
                    // divisor this sensor's vol-time normalisation depends on (ADR-0113).
                    continue;
                }
                forecaster.observe(mark.instrumentId(), peerGroup, mark.price(), atMillis);
            }
            if (marketNow == Long.MIN_VALUE) {
                return; // nothing fresh on the tape this pass
            }
            var readings = forecaster.sweep(marketNow);
            lastSweep = readings;
            for (var reading : readings.values()) {
                registry.submitCrossSectionalReversion(reading.instrumentId(), reading.score());
                BigDecimal price = pricesSeen.get(reading.instrumentId());
                if (telemetry != null && reading.score() != 0.0 && price != null) {
                    telemetry.record(SOURCE, reading.instrumentId(),
                            reading.score() > 0 ? Side.BUY : Side.SELL, price);
                }
            }
        } catch (Exception e) {
            log.debug("cross-sectional reversion sensor tick failed: {}", e.toString());
        }
    }

    /** The name's asset class from the instrument master — the universe's single source (invariant 9). */
    private String peerGroupFor(String instrumentId) {
        if (refs == null || instrumentId == null) {
            return null;
        }
        var ref = refs.find(instrumentId).orElse(null);
        return ref == null ? null : ref.assetClass();
    }

    /**
     * Replays this instrument's stored recent prints into the window the first time we see it, so a
     * redeploy does not restart the lookback from zero (ADR-0071). Unlike the per-name sensors' seed
     * this one needs the stored TIMESTAMPS as well as the prices — the window is a span of provider
     * time, not a count of samples — so the points go in as they are stored rather than through
     * {@link SensorWarmup#seedPrices}, which thins a series to a sample cadence.
     *
     * <p>The seed is deliberately NOT recorded in the signal telemetry: a historical price is not a call
     * the desk made, and counting it would fabricate track record.
     */
    private void warmIfFirstSight(String instrumentId, String peerGroup, long providerNowMillis) {
        if (history == null || !seeded.add(instrumentId)) {
            return;
        }
        java.util.List<SensorWarmup.Point> points;
        try {
            points = history.since(instrumentId, providerNowMillis - forecaster.params().lookbackMillis());
        } catch (RuntimeException e) {
            return; // a history read must never stop a sensor from starting
        }
        if (points == null || points.isEmpty()) {
            log.info("cross-sectional reversion sensor cold-starting {} — no stored prints inside the "
                    + "lookback; it will speak once the window fills from the live tape", instrumentId);
            return;
        }
        for (var point : points) {
            if (point != null && point.timestampMillis() <= providerNowMillis) {
                forecaster.observe(instrumentId, peerGroup, point.price(), point.timestampMillis());
            }
        }
        log.info("cross-sectional reversion sensor warmed {} from {} stored prints", instrumentId, points.size());
    }

    @Override
    public void close() {
        if (task != null) {
            task.cancel(false); // cancel our task only — the shared pool is owned elsewhere
        }
    }
}
