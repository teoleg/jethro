package io.jethro.trading.marketdata.sim;

import java.util.SplittableRandom;

/**
 * Deterministic random-walk price generator — the seedable heart of the sim adapter
 * (ADR-0009: fixed seeds in tests). Pure and allocation-free after construction.
 * Prices are scaled longs (PRICE_SCALE = 1e-6 units) and never drop below one cent.
 *
 * <p>The step is <em>proportional</em> to the current price, with a per-instrument
 * magnitude in millionths (1e-6 of price per tick) so the walk can be calibrated to a
 * realistic annualized volatility: {@code maxStepMicro ≈ σ_annual · √(Δt/T_year) · √3 ·
 * 1e6} (√3 matches a uniform step's std-dev to σ). E.g. 25% vol at a 100ms tick →
 * maxStep ≈ 56 → a typical 5-minute move of ~0.18%, not the multi-percent jumps of an
 * uncalibrated walk.
 */
public final class SimTickGenerator {

    private static final long MIN_PRICE_SCALED = 10_000L;      // 0.01 in 1e-6 units
    /** Legacy default: ~realistic for a 100ms tick at ~20% annual vol. */
    private static final long DEFAULT_MAX_STEP_MICRO = 45;

    private final SplittableRandom random;
    private final long[] pricesScaled;
    private final long[] maxStepMicros;

    /** Convenience: every instrument starts at the same price, default step. */
    public SimTickGenerator(long seed, int instrumentCount, long startPriceScaled) {
        this(seed, uniform(instrumentCount, startPriceScaled),
                uniform(instrumentCount, DEFAULT_MAX_STEP_MICRO));
    }

    /** Per-instrument start prices, default step (kept for existing callers/tests). */
    public SimTickGenerator(long seed, long[] startPricesScaled) {
        this(seed, startPricesScaled, uniform(startPricesScaled.length, DEFAULT_MAX_STEP_MICRO));
    }

    /**
     * Per-instrument start prices and per-tick max step in millionths of price, both
     * aligned to the adapter's instrument order.
     */
    public SimTickGenerator(long seed, long[] startPricesScaled, long[] maxStepMicros) {
        if (startPricesScaled.length == 0) {
            throw new IllegalArgumentException("at least one start price required");
        }
        if (maxStepMicros.length != startPricesScaled.length) {
            throw new IllegalArgumentException("step config must align with instruments");
        }
        for (long price : startPricesScaled) {
            if (price < MIN_PRICE_SCALED) {
                throw new IllegalArgumentException("startPriceScaled below minimum price");
            }
        }
        for (long step : maxStepMicros) {
            if (step < 1) {
                throw new IllegalArgumentException("maxStepMicro must be >= 1");
            }
        }
        this.random = new SplittableRandom(seed);
        this.pricesScaled = startPricesScaled.clone();
        this.maxStepMicros = maxStepMicros.clone();
    }

    private static long[] uniform(int instrumentCount, long value) {
        if (instrumentCount <= 0) {
            throw new IllegalArgumentException("instrumentCount must be positive");
        }
        long[] values = new long[instrumentCount];
        java.util.Arrays.fill(values, value);
        return values;
    }

    /** Advances the walk for one instrument and returns its new scaled price. */
    public long nextPriceScaled(int instrumentIndex) {
        long price = pricesScaled[instrumentIndex];
        long max = maxStepMicros[instrumentIndex];
        // Proportional step: ± max millionths of the current price.
        long step = random.nextLong(2 * max + 1) - max;
        long next = Math.max(MIN_PRICE_SCALED, price + price * step / 1_000_000L);
        pricesScaled[instrumentIndex] = next;
        return next;
    }

    /** Deterministic pseudo-random trade quantity: 1..1000 whole units, scaled. */
    public long nextQuantityScaled() {
        return (random.nextInt(1000) + 1) * 1_000_000L;
    }
}
