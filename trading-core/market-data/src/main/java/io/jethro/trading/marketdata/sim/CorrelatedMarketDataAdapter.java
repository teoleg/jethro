package io.jethro.trading.marketdata.sim;

import io.jethro.trading.marketdata.MarketDataAdapter;
import io.jethro.trading.marketdata.MarketDataListener;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Simulated feed driven by the correlated cross-asset factor model (ADR-0026). Same
 * contract as the legacy sim adapter — seeded and deterministic (ADR-0009), a trade per
 * instrument per tick — but every instrument's move comes from ONE joint draw: single
 * names ride the equity factor, FX rides the USD factor, and the SOFR curve's level/slope
 * are the RATES factors, so the Treasury futures and swaps priced from the curve move in
 * concert with everything else. Provider name stays {@code sim}.
 */
public final class CorrelatedMarketDataAdapter implements MarketDataAdapter {

    public static final String NAME = "sim";

    /** Close→open share of a trading day's variance (stylized US-equity overnight fraction). */
    static final double OVERNIGHT_DAY_FRACTION = 0.3;

    private final CorrelatedFactorSimulator sim;
    private final CurveMarkSource curveSim;        // nullable: no curve marks when absent
    private final String[] factorIds;              // priced by the factor model
    private final String[] linkedIds;              // priced FROM the curve (Treasury futures)
    private final Quotes.QuoteSpec[] factorSpecs;  // per-id quote synthesis; null = no quotes
    private final Quotes.QuoteSpec[] linkedSpecs;
    private final Quotes.QuoteSpec[] swapSpecs;
    private final long tickIntervalNanos;
    private final long ticksPerDay;                // 0 disables overnight gaps
    private final SimControl control;              // live control panel (ADR-0031)
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread feedThread;
    private volatile long dayIndexV;               // completed simulated trading days

    /**
     * @param instrumentIds full universe to emit; ids the curve links (ZT/ZF/ZN/ZB) are priced
     *                      from the curve, the rest from the factor model.
     */
    public CorrelatedMarketDataAdapter(long seed, FactorModelConfig config, List<String> instrumentIds,
                                       long[] startPricesScaled, CurveMarkSource curveSim,
                                       long tickIntervalNanos, double simSecondsPerDay) {
        this(seed, config, instrumentIds, startPricesScaled, curveSim, tickIntervalNanos,
                simSecondsPerDay, id -> null);
    }

    /** @param quoteSpecs per-instrument bid/ask synthesis around the emitted mid (ADR-0025) —
     *                    the SAME spreads the execution model charges; null spec = no quotes. */
    public CorrelatedMarketDataAdapter(long seed, FactorModelConfig config, List<String> instrumentIds,
                                       long[] startPricesScaled, CurveMarkSource curveSim,
                                       long tickIntervalNanos, double simSecondsPerDay,
                                       Quotes.QuoteSpecSource quoteSpecs) {
        if (instrumentIds.isEmpty()) {
            throw new IllegalArgumentException("at least one instrument required");
        }
        List<String> factor = new ArrayList<>();
        List<String> linked = new ArrayList<>();
        List<Long> factorStarts = new ArrayList<>();
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
        // The panel controls the factor-priced names (curve-linked futures/swaps derive from
        // the rates factors, reachable via the regime/speed dials).
        this.control = new SimControl(tickIntervalNanos, factor);
        this.sim = new CorrelatedFactorSimulator(seed, config, factor,
                factorStarts.stream().mapToLong(Long::longValue).toArray(),
                tickIntervalNanos / 1_000_000_000.0, simSecondsPerDay, control);
    }

    /** The live control panel (ADR-0031) — sim-only; the REST/UI surface mutating it is
     *  hard-gated to {@code feedMode == SIM}. */
    public SimControl control() {
        return control;
    }

    private static Quotes.QuoteSpec[] specsFor(String[] ids, Quotes.QuoteSpecSource source) {
        Quotes.QuoteSpec[] specs = new Quotes.QuoteSpec[ids.length];
        for (int i = 0; i < ids.length; i++) {
            specs[i] = source.specFor(ids[i]);
        }
        return specs;
    }

    /** Emits a synthesized top-of-book quote around the mid when the instrument has a spec. */
    private static void quote(MarketDataListener listener, String id, Quotes.QuoteSpec spec,
                              long midScaled, long now) {
        if (spec != null) {
            listener.onQuote(id, Quotes.bidScaled(midScaled, spec), Quotes.askScaled(midScaled, spec), now, now);
        }
    }

    /** Current market regime — narrative feed + regime-aware sizing read this. */
    public MarketRegime regime() {
        return sim.regime();
    }

    /** Completed simulated trading days on THIS tape (tick-counted) — the session calendar
     *  keys to this so day boundaries and overnight gaps never drift apart (ADR-0027). */
    public long simDayIndex() {
        return dayIndexV;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void start(MarketDataListener listener) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("sim adapter already started");
        }
        Thread thread = new Thread(() -> run(listener), "sim-feed");
        thread.setDaemon(true);
        feedThread = thread;
        thread.start();
    }

    private void run(MarketDataListener listener) {
        long tickCount = 0;
        while (running.get()) {
            // Live pause dial (ADR-0031): idle without advancing the tape or the day counter.
            if (control.paused()) {
                java.util.concurrent.locks.LockSupport.parkNanos(control.pausePollNanos());
                continue;
            }
            long now = System.currentTimeMillis();
            // Overnight gap at each simulated day boundary (ADR-0026/0027): one correlated
            // close→open jump between consecutive ticks; the curve consumes its rates deltas
            // so futures/swaps gap coherently too. Tick-counted — in lockstep with the sim's
            // own dtDays time base — and exported as simDayIndex() so the session calendar
            // keys to the SAME boundary (a wall clock would drift by scheduler overhead).
            if (ticksPerDay > 0 && tickCount > 0 && tickCount % ticksPerDay == 0) {
                dayIndexV = tickCount / ticksPerDay;
                sim.overnightGap(OVERNIGHT_DAY_FRACTION);
                if (curveSim != null) {
                    curveSim.applyExternalStep(sim.lastLevelDelta(), sim.lastSlopeDelta());
                }
            }
            tickCount++;
            sim.nextTick();
            for (int i = 0; i < factorIds.length; i++) {
                long mid = sim.priceScaled(i);
                long qty = scaleVolume(sim.nextQuantityScaled(), control.volumeScale(i));
                listener.onTrade(factorIds[i], mid, qty, now, now);
                quote(listener, factorIds[i], factorSpecs[i], mid, now);
            }
            if (curveSim != null) {
                // The curve consumes the SAME tick's RATES innovations — cross-asset coherence.
                curveSim.applyExternalStep(sim.lastLevelDelta(), sim.lastSlopeDelta());
                for (int i = 0; i < linkedIds.length; i++) {
                    long mid = curveSim.linkedPriceScaled(linkedIds[i]);
                    listener.onTrade(linkedIds[i], mid, sim.nextQuantityScaled(), now, now);
                    quote(listener, linkedIds[i], linkedSpecs[i], mid, now);
                }
                for (int t = 0; t < CurveMarkSource.TENOR_IDS.length; t++) {
                    listener.onTrade(CurveMarkSource.TENOR_IDS[t],
                            curveSim.rateScaledPercent(t), 1_000_000L, now, now);
                }
                // The DISTINCT US Treasury par curve (TSY = SOFR + swap spread; futures key off it).
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

    /** Applies the per-instrument volume dial (ADR-0031), keeping a minimum 1-unit trade so a
     *  ×0 dial still prints a tape (volume 0 would look like a dropped tick). */
    private static long scaleVolume(long quantityScaled, double scale) {
        if (scale == 1.0) {
            return quantityScaled;
        }
        return Math.max(1_000_000L, Math.round(quantityScaled * scale));
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
