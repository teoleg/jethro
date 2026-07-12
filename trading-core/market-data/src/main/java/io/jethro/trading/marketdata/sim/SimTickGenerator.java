package io.jethro.trading.marketdata.sim;

import java.util.SplittableRandom;

/**
 * Deterministic random-walk price generator — the seedable heart of the sim adapter
 * (ADR-0009: fixed seeds in tests). Pure and allocation-free after construction.
 * Prices are scaled longs (PRICE_SCALE = 1e-6 units) and never drop below one cent.
 */
public final class SimTickGenerator {

    private static final long MIN_PRICE_SCALED = 10_000L;      // 0.01 in 1e-6 units
    private static final long STEP_UNIT_SCALED = 1_000L;       // 0.001 in 1e-6 units

    private final SplittableRandom random;
    private final long[] pricesScaled;

    public SimTickGenerator(long seed, int instrumentCount, long startPriceScaled) {
        if (instrumentCount <= 0) {
            throw new IllegalArgumentException("instrumentCount must be positive");
        }
        if (startPriceScaled < MIN_PRICE_SCALED) {
            throw new IllegalArgumentException("startPriceScaled below minimum price");
        }
        this.random = new SplittableRandom(seed);
        this.pricesScaled = new long[instrumentCount];
        java.util.Arrays.fill(pricesScaled, startPriceScaled);
    }

    /** Advances the walk for one instrument and returns its new scaled price. */
    public long nextPriceScaled(int instrumentIndex) {
        // Step of -100..+100 units of 0.001 → ±0.1 per tick
        long delta = (random.nextInt(201) - 100) * STEP_UNIT_SCALED;
        long next = Math.max(MIN_PRICE_SCALED, pricesScaled[instrumentIndex] + delta);
        pricesScaled[instrumentIndex] = next;
        return next;
    }

    /** Deterministic pseudo-random trade quantity: 1..1000 whole units, scaled. */
    public long nextQuantityScaled() {
        return (random.nextInt(1000) + 1) * 1_000_000L;
    }
}
