package io.jethro.trading.marketdata.sim;

import java.util.SplittableRandom;

/**
 * Deterministic random-walk price generator — the seedable heart of the sim adapter
 * (ADR-0009: fixed seeds in tests). Pure and allocation-free after construction.
 * Prices are scaled longs (PRICE_SCALE = 1e-6 units) and never drop below one cent.
 *
 * <p>The step is <em>proportional</em> to the current price (±{@value #MAX_STEP_BPS}
 * bps per tick), so the walk behaves sensibly across price scales — an ES future at
 * 5000 and EURUSD at 1.08 both move ~0.1% per tick rather than a fixed absolute amount.
 */
public final class SimTickGenerator {

    private static final long MIN_PRICE_SCALED = 10_000L;      // 0.01 in 1e-6 units
    private static final int MAX_STEP_BPS = 10;                // ±0.10% per tick

    private final SplittableRandom random;
    private final long[] pricesScaled;

    /** Convenience: every instrument starts at the same price. */
    public SimTickGenerator(long seed, int instrumentCount, long startPriceScaled) {
        this(seed, uniform(instrumentCount, startPriceScaled));
    }

    /** Per-instrument start prices (aligned to the adapter's instrument order). */
    public SimTickGenerator(long seed, long[] startPricesScaled) {
        if (startPricesScaled.length == 0) {
            throw new IllegalArgumentException("at least one start price required");
        }
        for (long price : startPricesScaled) {
            if (price < MIN_PRICE_SCALED) {
                throw new IllegalArgumentException("startPriceScaled below minimum price");
            }
        }
        this.random = new SplittableRandom(seed);
        this.pricesScaled = startPricesScaled.clone();
    }

    private static long[] uniform(int instrumentCount, long startPriceScaled) {
        if (instrumentCount <= 0) {
            throw new IllegalArgumentException("instrumentCount must be positive");
        }
        long[] prices = new long[instrumentCount];
        java.util.Arrays.fill(prices, startPriceScaled);
        return prices;
    }

    /** Advances the walk for one instrument and returns its new scaled price. */
    public long nextPriceScaled(int instrumentIndex) {
        long price = pricesScaled[instrumentIndex];
        // Proportional step: ±MAX_STEP_BPS basis points of the current price.
        long bps = random.nextInt(2 * MAX_STEP_BPS + 1) - MAX_STEP_BPS;
        long next = Math.max(MIN_PRICE_SCALED, price + price * bps / 10_000L);
        pricesScaled[instrumentIndex] = next;
        return next;
    }

    /** Deterministic pseudo-random trade quantity: 1..1000 whole units, scaled. */
    public long nextQuantityScaled() {
        return (random.nextInt(1000) + 1) * 1_000_000L;
    }
}
