package io.jethro.trading.marketdata.sim;

import io.jethro.trading.marketdata.MarketDataAdapter;
import io.jethro.trading.marketdata.MarketDataListener;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * History-anchored sim feed (ADR-0032): prices the price-quoted universe from the
 * {@link HistoricalBootstrapSimulator} — a block bootstrap of real daily returns — and emits the
 * instrument's <b>real (bootstrapped) volume</b> as the print size, so measured ADV and the flow
 * a model reads are grounded in actual history rather than a random draw. Same {@code sim}
 * provider name and same emission shape as {@link CorrelatedMarketDataAdapter}; the rates curve
 * runs alongside on its own seedable walk (equity history can't imply a curve), and the
 * curve-linked Treasury futures price off it exactly as before. Reads the live {@link SimControl}.
 */
public final class HistoricalMarketDataAdapter implements MarketDataAdapter {

    public static final String NAME = "sim";

    private final HistoricalBootstrapSimulator sim;
    private final CurveMarkSource curveSim;        // nullable: no curve marks when absent
    private final String[] factorIds;              // priced by the bootstrap
    private final String[] linkedIds;              // priced FROM the curve (Treasury futures)
    private final Quotes.QuoteSpec[] factorSpecs;
    private final Quotes.QuoteSpec[] linkedSpecs;
    private final Quotes.QuoteSpec[] swapSpecs;
    private final long tickIntervalNanos;
    private final long ticksPerDay;                // >0: per-tick volume = daily / ticksPerDay
    private final SimControl control;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread feedThread;
    private volatile long dayIndexV;

    public HistoricalMarketDataAdapter(long seed, HistoricalSnapshot snapshot, List<String> instrumentIds,
                                       long[] startPricesScaled, CurveMarkSource curveSim,
                                       long tickIntervalNanos, double simSecondsPerDay,
                                       double meanBlockLength, Quotes.QuoteSpecSource quoteSpecs) {
        if (instrumentIds.isEmpty()) {
            throw new IllegalArgumentException("at least one instrument required");
        }
        List<String> factor = new ArrayList<>();
        List<Long> factorStarts = new ArrayList<>();
        List<String> linked = new ArrayList<>();
        for (int i = 0; i < instrumentIds.size(); i++) {
            String id = instrumentIds.get(i);
            if (curveSim != null && curveSim.isLinked(id)) {
                linked.add(id);
            } else {
                factor.add(id);
                factorStarts.add(startPricesScaled[i]);
            }
        }
        this.factorIds = factor.toArray(String[]::new);
        this.linkedIds = linked.toArray(String[]::new);
        this.factorSpecs = specsFor(this.factorIds, quoteSpecs);
        this.linkedSpecs = specsFor(this.linkedIds, quoteSpecs);
        this.swapSpecs = specsFor(CurveMarkSource.SWAP_IDS, quoteSpecs);
        this.curveSim = curveSim;
        this.tickIntervalNanos = tickIntervalNanos;
        this.ticksPerDay = Math.round(simSecondsPerDay / (tickIntervalNanos / 1_000_000_000.0));
        this.control = new SimControl(tickIntervalNanos, factor);
        this.sim = new HistoricalBootstrapSimulator(seed, snapshot, factor,
                factorStarts.stream().mapToLong(Long::longValue).toArray(), meanBlockLength, control);
    }

    /** The live control panel (ADR-0031) — sim-only, gated on {@code feedMode == SIM}. */
    public SimControl control() {
        return control;
    }

    /** No regime concept on the historical engine — always CALM (the panel's regime override is
     *  a no-op here; use vol/drift dials or reseed instead). */
    public MarketRegime regime() {
        return MarketRegime.CALM;
    }

    public long simDayIndex() {
        return dayIndexV;
    }

    private static Quotes.QuoteSpec[] specsFor(String[] ids, Quotes.QuoteSpecSource source) {
        Quotes.QuoteSpec[] specs = new Quotes.QuoteSpec[ids.length];
        for (int i = 0; i < ids.length; i++) {
            specs[i] = source.specFor(ids[i]);
        }
        return specs;
    }

    private static void quote(MarketDataListener listener, String id, Quotes.QuoteSpec spec,
                              long midScaled, long now) {
        if (spec != null) {
            listener.onQuote(id, Quotes.bidScaled(midScaled, spec), Quotes.askScaled(midScaled, spec), now, now);
        }
    }

    /** Per-tick print size from the day's real volume, honouring the volume dial; ≥1 unit. */
    private long perTickQtyScaled(long dailyVolume, double volumeScale) {
        long perDayTicks = ticksPerDay > 0 ? ticksPerDay : 1;
        double perTick = (double) dailyVolume / perDayTicks * volumeScale;
        return Math.max(1_000_000L, Math.round(perTick * 1_000_000));
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void start(MarketDataListener listener) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("historical sim adapter already started");
        }
        Thread thread = new Thread(() -> run(listener), "sim-feed");
        thread.setDaemon(true);
        feedThread = thread;
        thread.start();
    }

    private void run(MarketDataListener listener) {
        long tickCount = 0;
        while (running.get()) {
            if (control.paused()) {
                java.util.concurrent.locks.LockSupport.parkNanos(control.pausePollNanos());
                continue;
            }
            long now = System.currentTimeMillis();
            if (ticksPerDay > 0 && tickCount > 0 && tickCount % ticksPerDay == 0) {
                dayIndexV = tickCount / ticksPerDay;
            }
            tickCount++;
            sim.nextTick();
            for (int i = 0; i < factorIds.length; i++) {
                long mid = sim.priceScaled(i);
                long qty = perTickQtyScaled(sim.currentDailyVolume(i), control.volumeScale(i));
                listener.onTrade(factorIds[i], mid, qty, now, now);
                quote(listener, factorIds[i], factorSpecs[i], mid, now);
            }
            if (curveSim != null) {
                curveSim.step(); // the curve runs on its own seedable walk under this engine
                for (int i = 0; i < linkedIds.length; i++) {
                    long mid = curveSim.linkedPriceScaled(linkedIds[i]);
                    listener.onTrade(linkedIds[i], mid, 1_000_000L, now, now);
                    quote(listener, linkedIds[i], linkedSpecs[i], mid, now);
                }
                for (int t = 0; t < CurveMarkSource.TENOR_IDS.length; t++) {
                    listener.onTrade(CurveMarkSource.TENOR_IDS[t],
                            curveSim.rateScaledPercent(t), 1_000_000L, now, now);
                }
                for (int t = 0; t < CurveMarkSource.TSY_TENOR_IDS.length; t++) {
                    listener.onTrade(CurveMarkSource.TSY_TENOR_IDS[t],
                            curveSim.tsyRateScaledPercent(t), 1_000_000L, now, now);
                }
                for (int s = 0; s < CurveMarkSource.SWAP_IDS.length; s++) {
                    long mid = curveSim.swapParScaledPercent(s);
                    listener.onTrade(CurveMarkSource.SWAP_IDS[s], mid, 1_000_000L, now, now);
                    quote(listener, CurveMarkSource.SWAP_IDS[s], swapSpecs[s], mid, now);
                }
            }
            java.util.concurrent.locks.LockSupport.parkNanos(control.effectiveTickIntervalNanos());
        }
    }

    @Override
    public void stop() {
        running.set(false);
        Thread thread = feedThread;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
