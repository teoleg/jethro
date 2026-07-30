package io.jethro.app.fusion;

import io.jethro.app.signal.SignalTelemetry;
import io.jethro.app.trading.TradingCoreLifecycle;
import io.jethro.domain.Side;
import io.jethro.trading.algo.strategy.EwmacTrendForecaster;
import io.jethro.trading.riskpnl.InstrumentRefSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Drives the ADR-0130 market-index trend sensor: it runs the SAME {@link EwmacTrendForecaster} the per-name
 * trend sensor uses, but on the BROAD MARKET INDEX (default {@code SPX}), and carries that one market read
 * to every equity as its {@code indextrend} forecast. The market factor is the single biggest driver of a
 * single name's return, and it is slow (an index trend measured over hours) — a real, low-turnover overlay
 * on top of the fast per-name sensors.
 *
 * <p>It is a <b>forecast source, not an order source</b> — identical contract to the other sensors (ADR-0066
 * / ADR-0121). It publishes a claim per name into the same {@link ForecastRegistry}; whether any of it
 * becomes a trade is decided downstream by machinery it cannot influence: the edge gate, the conviction
 * floor, the backtest-support veto, and the deterministic floor (pre-trade guardrail, firm breaker). It
 * records each call in the phase-1 telemetry, so the market-trend overlay is measured on its own realised
 * expectancy and earns its fusion weight — a new source arrives with no evidence and earns its allocation,
 * or doesn't.
 *
 * <p><b>Unit market beta (no invented dial).</b> Every equity carries the same market-trend score; no
 * per-name beta multiplier is applied, because a β default is a money dial we do not set silently (a
 * {@code β=1.0} placeholder is exactly the mistake the conventions warn about). The source's magnitude
 * comes from its measured expectancy, not a hand-set beta. Per-name / per-region beta is a tracked
 * follow-up.
 *
 * <p>Advances on PRINTS ({@link PrintClock}, ADR-0113): the index EWMAC steps only when the index tape
 * prints, and a name is published only when ITS tape prints, so a republished last-value mark never
 * fabricates a zero-return step or a phantom call. Warmed from durable history on first sight
 * ({@link SensorWarmup}, ADR-0071) so the market read boots calibrated. Confined to one scheduled thread
 * (the forecaster is not thread-safe).
 */
public final class IndexTrendForecastLifecycle implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(IndexTrendForecastLifecycle.class);

    /** The telemetry/source name — the key its measured edge and fusion weight are stored under. */
    public static final String SOURCE = SourceForecasts.INDEX_TREND;

    private final EwmacTrendForecaster forecaster;
    private final ForecastRegistry registry;
    private final TradingCoreLifecycle tradingCore;
    private final InstrumentRefSource refs;
    private final SignalTelemetry telemetry; // optional observer — null when signal telemetry is off
    private final SensorWarmup.History history; // optional — null means cold-start (ADR-0071)
    private final ScheduledExecutorService scheduler;
    private final long intervalSeconds;
    /** The broad market index the overlay reads (an INDEX row in the master, ADR-0129). */
    private final String marketIndexId;
    /** ADR-0131: when a still-cold name replays history again — touched only from the tick thread. */
    private final SensorReseed reseed;
    private final PrintClock printClock = new PrintClock();

    private Future<?> task;

    public IndexTrendForecastLifecycle(EwmacTrendForecaster forecaster, ForecastRegistry registry,
                                       TradingCoreLifecycle tradingCore, InstrumentRefSource refs,
                                       SignalTelemetry telemetry, SensorWarmup.History history,
                                       ScheduledExecutorService scheduler, long intervalSeconds,
                                       String marketIndexId) {
        this.forecaster = forecaster;
        this.registry = registry;
        this.tradingCore = tradingCore;
        this.refs = refs;
        this.telemetry = telemetry;
        this.history = history;
        this.scheduler = scheduler;
        this.intervalSeconds = Math.max(1, intervalSeconds);
        this.reseed = new SensorReseed(forecaster.warmupSamples());
        this.marketIndexId = marketIndexId;
    }

    public void start() {
        task = scheduler.scheduleWithFixedDelay(this::tick, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("index-trend sensor started (EWMAC on {} → carried to every equity, every {}s) — ADR-0130 "
                + "forecast source, places no orders", marketIndexId, intervalSeconds);
    }

    /** The forecaster's current market-index reading (for the API/UI and tests). */
    public EwmacTrendForecaster forecaster() {
        return forecaster;
    }

    private void tick() {
        try {
            var runtime = tradingCore == null ? null : tradingCore.runtime();
            if (runtime == null || refs == null) {
                return;
            }
            var snapshot = runtime.markCache().snapshot();

            // 1) Advance the market index's EWMAC on ITS OWN prints.
            for (var mark : snapshot) {
                if (!marketIndexId.equals(mark.instrumentId())) {
                    continue;
                }
                if (!mark.stale()) {
                    warmWhileCold(mark.instrumentId(), mark.providerTimestamp());
                    if (printClock.advanced(mark.instrumentId(), mark.providerTimestamp())) {
                        forecaster.update(mark.instrumentId(), mark.price());
                    }
                }
                break; // the index appears once
            }
            var reading = forecaster.readingFor(marketIndexId);
            double indexScore = reading == null ? Double.NaN : reading.score();
            if (!Double.isFinite(indexScore)) {
                return; // no market read yet (index not marked / not warm) — publish nothing
            }

            // 2) Carry the market trend to each EQUITY as its OWN tape prints.
            for (var mark : snapshot) {
                if (mark.stale()) {
                    continue; // warm-loaded, not yet refreshed by the live feed (invariant 4)
                }
                var ref = refs.find(mark.instrumentId()).orElse(null);
                if (ref == null || !"EQUITY".equals(ref.assetClass())) {
                    continue; // the market overlay applies to equities; the index itself is not traded
                }
                if (!printClock.advanced(mark.instrumentId(), mark.providerTimestamp())) {
                    continue; // ADR-0113 — no fresh print, do not fabricate a call
                }
                registry.submitIndexTrend(mark.instrumentId(), indexScore);
                if (telemetry != null && indexScore != 0.0) {
                    telemetry.record(SOURCE, mark.instrumentId(),
                            indexScore > 0 ? Side.BUY : Side.SELL, mark.price());
                }
            }
        } catch (Exception e) {
            log.debug("index-trend sensor tick failed: {}", e.toString());
        }
    }

    /** Warm the market index's EWMAC from durable history (ADR-0071), so the overlay boots calibrated
     *  instead of spending its whole warm-up silent after every redeploy. Re-seeds while the index is
     *  still cold (ADR-0131) — a boot-time read of a store that ends in an outage or an overnight gap
     *  hands over a couple of prices, and seeding once abandoned the overlay there for the whole
     *  process. The replay goes into a state just dropped ({@code forget}); safe because it is cold. */
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
            log.info("index-trend sensor warmed {} from {} stored prices (needs {}) — warm", instrumentId, n, needed);
        } else {
            log.warn("index-trend sensor still cold for {} after seeding {} of {} stored prices — no market "
                    + "overlay until {}'s mark history accumulates its warm-up span; re-seeding every {} "
                    + "sightings until it does (ADR-0131)", instrumentId, n, needed, instrumentId, needed);
        }
    }

    @Override
    public void close() {
        if (task != null) {
            task.cancel(false); // cancel our task only — the shared pool is owned elsewhere
        }
    }
}
