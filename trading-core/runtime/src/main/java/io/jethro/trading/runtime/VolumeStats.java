package io.jethro.trading.runtime;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live traded-volume statistics per instrument (ADR-0032, phase 1) — the market path's volume
 * had been dropped at the ring buffer (only price fed the mark), so ADV was a static config
 * number and no consumer saw flow. This tracks it as trades stream through the consumer loop.
 *
 * <p>Measures in <b>count-EWMA</b> space (per-trade, not per-wall-second) so it is invariant to
 * the sim speed dial (ADR-0031): cranking the tick rate produces more trades but does not
 * inflate the per-trade average. Two half-lives give a stable long-run average (→ ADV, scaled
 * by the caller's trades-per-day) and a short burst estimate; their ratio is the relative
 * volume that a volume-confirmed signal reads (phase 4).
 *
 * <p>Values are in raw {@code price×qty} units (scaled-long products as {@code double}); the app
 * layer multiplies by the contract multiplier for a USD figure. These are execution/analytics
 * statistics (the ADR-0025 impact model is already {@code double}), never a money-ledger value —
 * invariant 1 governs prices/quantities/PnL, not a derived volume estimate.
 *
 * <p>Thread-safety: the consumer loop is the single writer (per-instrument {@code volatile}
 * fields); any thread may read. First sighting of an instrument allocates one {@link Stat}; the
 * steady-state hot path is a map get + field updates, no per-trade allocation.
 */
public final class VolumeStats {

    /** Half-lives in TRADES (count-based) — short catches bursts, long is the ADV baseline. */
    private static final double SHORT_HALFLIFE_TRADES = 30;
    private static final double LONG_HALFLIFE_TRADES = 3_000;
    private static final double PRICE_SCALE = 1_000_000.0;
    private static final double QTY_SCALE = 1_000_000.0;

    private static final double SHORT_ALPHA = alpha(SHORT_HALFLIFE_TRADES);
    private static final double LONG_ALPHA = alpha(LONG_HALFLIFE_TRADES);

    private final Map<String, Stat> byInstrument = new ConcurrentHashMap<>();

    private static double alpha(double halfLifeTrades) {
        return 1.0 - Math.pow(2.0, -1.0 / halfLifeTrades);
    }

    /** Records one trade print (consumer-loop thread). */
    public void record(String instrumentId, long priceScaled, long qtyScaled) {
        double notional = (priceScaled / PRICE_SCALE) * (qtyScaled / QTY_SCALE);
        byInstrument.computeIfAbsent(instrumentId, k -> new Stat()).accept(notional);
    }

    /** Smoothed average traded notional per print (raw price×qty units), or 0 if never seen. */
    public double avgTradeNotional(String instrumentId) {
        Stat s = byInstrument.get(instrumentId);
        return s == null ? 0.0 : s.longEwma;
    }

    /** Recent-vs-baseline volume ratio (~1 normal, &gt;1 a surge, &lt;1 thin); 1.0 until warm. */
    public double relativeVolume(String instrumentId) {
        Stat s = byInstrument.get(instrumentId);
        if (s == null || s.longEwma <= 0) {
            return 1.0;
        }
        return s.shortEwma / s.longEwma;
    }

    /** Trades observed for the instrument — a warm-up gate before trusting the average. */
    public long sampleCount(String instrumentId) {
        Stat s = byInstrument.get(instrumentId);
        return s == null ? 0 : s.count;
    }

    private static final class Stat {
        private volatile double shortEwma;
        private volatile double longEwma;
        private volatile long count;

        void accept(double notional) {
            if (count == 0) {
                shortEwma = notional;
                longEwma = notional;
            } else {
                shortEwma += SHORT_ALPHA * (notional - shortEwma);
                longEwma += LONG_ALPHA * (notional - longEwma);
            }
            count++;
        }
    }
}
