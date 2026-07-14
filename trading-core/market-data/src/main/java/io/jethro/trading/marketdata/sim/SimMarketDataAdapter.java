package io.jethro.trading.marketdata.sim;

import io.jethro.trading.marketdata.MarketDataAdapter;
import io.jethro.trading.marketdata.MarketDataListener;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Seedable simulated feed (ADR-0009): the default adapter in local dev and tests —
 * the platform never depends on market hours or a paid feed. Emits a trade per
 * instrument every {@code tickIntervalNanos}, prices from a deterministic random walk.
 */
public final class SimMarketDataAdapter implements MarketDataAdapter {

    public static final String NAME = "sim";

    private final SimTickGenerator generator;
    private final String[] instrumentIds; // constant references: no per-tick allocation
    private final long tickIntervalNanos;
    private final CurveMarkSource curveSim; // nullable: no curve marks when absent
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread feedThread;

    /** Convenience: every instrument starts at the same price. */
    public SimMarketDataAdapter(long seed, List<String> instrumentIds, long startPriceScaled, long tickIntervalNanos) {
        this(seed, instrumentIds, uniform(instrumentIds.size(), startPriceScaled), tickIntervalNanos);
    }

    /** Per-instrument start prices, aligned to {@code instrumentIds} order; default vol. */
    public SimMarketDataAdapter(long seed, List<String> instrumentIds, long[] startPricesScaled, long tickIntervalNanos) {
        this(seed, instrumentIds, startPricesScaled, null, tickIntervalNanos);
    }

    /** Per-instrument start prices and per-tick max step (millionths of price); null steps = default. */
    public SimMarketDataAdapter(long seed, List<String> instrumentIds, long[] startPricesScaled,
                                long[] maxStepMicros, long tickIntervalNanos) {
        this(seed, instrumentIds, startPricesScaled, maxStepMicros, false, tickIntervalNanos);
    }

    /** Full control: per-instrument prices/steps plus correlated market regimes (trend/vol/shock). */
    public SimMarketDataAdapter(long seed, List<String> instrumentIds, long[] startPricesScaled,
                                long[] maxStepMicros, boolean regimesEnabled, long tickIntervalNanos) {
        this(seed, instrumentIds, startPricesScaled, maxStepMicros, regimesEnabled, null, tickIntervalNanos);
    }

    /** As above plus a curve simulator whose SOFR tenor rates are published as marks (phase 4). */
    public SimMarketDataAdapter(long seed, List<String> instrumentIds, long[] startPricesScaled,
                                long[] maxStepMicros, boolean regimesEnabled,
                                CurveMarkSource curveSim, long tickIntervalNanos) {
        if (instrumentIds.isEmpty()) {
            throw new IllegalArgumentException("at least one instrument required");
        }
        if (startPricesScaled.length != instrumentIds.size()) {
            throw new IllegalArgumentException("start prices must align with instruments");
        }
        this.generator = maxStepMicros == null
                ? new SimTickGenerator(seed, startPricesScaled)
                : new SimTickGenerator(seed, startPricesScaled, maxStepMicros, regimesEnabled);
        this.instrumentIds = instrumentIds.toArray(String[]::new);
        this.tickIntervalNanos = tickIntervalNanos;
        this.curveSim = curveSim;
    }

    /** Current sim market regime (observability). */
    public MarketRegime regime() {
        return generator.regime();
    }

    private static long[] uniform(int count, long startPriceScaled) {
        long[] prices = new long[count];
        java.util.Arrays.fill(prices, startPriceScaled);
        return prices;
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
            int shockSign = 0;
            boolean shockCaptured = false;
            for (int i = 0; i < instrumentIds.length; i++) {
                long price;
                if (curveSim != null && curveSim.isLinked(instrumentIds[i])) {
                    price = curveSim.linkedPriceScaled(instrumentIds[i]); // priced FROM the curve
                } else {
                    price = generator.nextPriceScaled(i);
                    if (!shockCaptured) {
                        // A shock lasts one correlated round and is cleared as the round
                        // wraps — read its sign while it is definitely still in flight.
                        shockSign = generator.shockSign();
                        shockCaptured = true;
                    }
                }
                long qty = generator.nextQuantityScaled();
                // Sim is its own provider: provider ts == ingest ts
                listener.onTrade(instrumentIds[i], price, qty, now, now);
            }
            if (curveSim != null) {
                // The curve shares the regime (trends drift the level, VOLATILE scales
                // factor vol, shocks jump it) so rates have episodes like equities do.
                curveSim.step(generator.regime(), shockSign);
                // Curve tenor rates ride the same mark pipeline as pseudo-instruments;
                // risk-pnl routes USD.SOFR.* to curve calibration, not to positions.
                for (int t = 0; t < CurveMarkSource.TENOR_IDS.length; t++) {
                    listener.onTrade(CurveMarkSource.TENOR_IDS[t],
                            curveSim.rateScaledPercent(t), 1_000_000L, now, now);
                }
                // The DISTINCT US Treasury par curve (TSY = SOFR + swap spread; futures key off it).
                for (int t = 0; t < CurveMarkSource.TSY_TENOR_IDS.length; t++) {
                    listener.onTrade(CurveMarkSource.TSY_TENOR_IDS[t],
                            curveSim.tsyRateScaledPercent(t), 1_000_000L, now, now);
                }
                // Swap par rates are REAL instrument marks (USD_IRS_*): they land in the
                // mark cache, tick on the Markets → Swaps tab, and are tradeable (V9).
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
