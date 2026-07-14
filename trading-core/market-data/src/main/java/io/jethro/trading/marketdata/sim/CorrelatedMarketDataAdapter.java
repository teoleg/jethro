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

    private final CorrelatedFactorSimulator sim;
    private final CurveMarkSource curveSim;        // nullable: no curve marks when absent
    private final String[] factorIds;              // priced by the factor model
    private final String[] linkedIds;              // priced FROM the curve (Treasury futures)
    private final long tickIntervalNanos;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread feedThread;

    /**
     * @param instrumentIds full universe to emit; ids the curve links (ZT/ZF/ZN/ZB) are priced
     *                      from the curve, the rest from the factor model.
     */
    public CorrelatedMarketDataAdapter(long seed, FactorModelConfig config, List<String> instrumentIds,
                                       long[] startPricesScaled, CurveMarkSource curveSim,
                                       long tickIntervalNanos, double simSecondsPerDay) {
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
        this.curveSim = curveSim;
        this.tickIntervalNanos = tickIntervalNanos;
        this.sim = new CorrelatedFactorSimulator(seed, config, factor,
                factorStarts.stream().mapToLong(Long::longValue).toArray(),
                tickIntervalNanos / 1_000_000_000.0, simSecondsPerDay);
    }

    /** Current market regime — narrative feed + regime-aware sizing read this. */
    public MarketRegime regime() {
        return sim.regime();
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
        while (running.get()) {
            long now = System.currentTimeMillis();
            sim.nextTick();
            for (int i = 0; i < factorIds.length; i++) {
                listener.onTrade(factorIds[i], sim.priceScaled(i), sim.nextQuantityScaled(), now, now);
            }
            if (curveSim != null) {
                // The curve consumes the SAME tick's RATES innovations — cross-asset coherence.
                curveSim.applyExternalStep(sim.lastLevelDelta(), sim.lastSlopeDelta());
                for (String id : linkedIds) {
                    listener.onTrade(id, curveSim.linkedPriceScaled(id), sim.nextQuantityScaled(), now, now);
                }
                for (int t = 0; t < CurveMarkSource.TENOR_IDS.length; t++) {
                    listener.onTrade(CurveMarkSource.TENOR_IDS[t],
                            curveSim.rateScaledPercent(t), 1_000_000L, now, now);
                }
                for (int s = 0; s < CurveMarkSource.SWAP_IDS.length; s++) {
                    listener.onTrade(CurveMarkSource.SWAP_IDS[s],
                            curveSim.swapParScaledPercent(s), 1_000_000L, now, now);
                }
            }
            java.util.concurrent.locks.LockSupport.parkNanos(tickIntervalNanos);
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
