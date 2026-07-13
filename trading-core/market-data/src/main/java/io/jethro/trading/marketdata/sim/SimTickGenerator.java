package io.jethro.trading.marketdata.sim;

import java.util.SplittableRandom;

/**
 * Deterministic random-walk price generator — the seedable heart of the sim adapter
 * (ADR-0009: fixed seeds in tests). Pure and allocation-free after construction.
 * Prices are scaled longs (PRICE_SCALE = 1e-6 units) and never drop below one cent.
 *
 * <p>The step is <em>proportional</em> to the current price, with a per-instrument
 * magnitude in millionths (1e-6 of price per tick) so the walk can be calibrated to a
 * realistic annualized volatility: {@code maxStepMicro ≈ σ_annual · √(Δt/T_year) · √3}.
 *
 * <p><b>Regimes</b> (optional): the market moves through seeded episodes — CALM,
 * TREND_UP/DOWN (a drift the momentum strategy can genuinely catch), VOLATILE (vol ×3),
 * and occasional one-tick SHOCK jumps — applied to <em>every</em> instrument at once, so
 * moves are correlated the way real markets correlate. Drift/vol scale with each
 * instrument's own step, so a trend means ~2σ over two minutes for AAPL and for a
 * Treasury future alike. Same seed → same regime path (still fully deterministic).
 */
public final class SimTickGenerator {

    private static final long MIN_PRICE_SCALED = 10_000L;      // 0.01 in 1e-6 units
    /** Legacy default: ~realistic for a 100ms tick at ~20% annual vol. */
    private static final long DEFAULT_MAX_STEP_MICRO = 45;

    // Regime dwell times in "rounds" (one round = one tick of every instrument; ~100ms).
    private static final int MIN_DWELL_ROUNDS = 600;           // ~1 min
    private static final int MAX_DWELL_ROUNDS = 3_600;         // ~6 min
    /** Chance that a regime change is a SHOCK: a one-tick correlated jump, then VOLATILE. */
    private static final double SHOCK_PROBABILITY = 0.08;
    // Shock jump magnitude in multiples of an instrument's max step (~1-3% for equities).
    private static final int SHOCK_MIN_STEPS = 150;
    private static final int SHOCK_MAX_STEPS = 500;

    private final SplittableRandom random;
    private final long[] pricesScaled;
    private final long[] maxStepMicros;
    private final boolean regimesEnabled;

    private MarketRegime regime = MarketRegime.CALM;
    private int dwellRoundsLeft;
    private int roundCursor;                 // counts instruments within the current round
    private long shockSteps;                 // signed, non-zero only on a shock round

    /** Convenience: every instrument starts at the same price, default step, no regimes. */
    public SimTickGenerator(long seed, int instrumentCount, long startPriceScaled) {
        this(seed, uniform(instrumentCount, startPriceScaled),
                uniform(instrumentCount, DEFAULT_MAX_STEP_MICRO), false);
    }

    /** Per-instrument start prices, default step, no regimes (kept for existing callers). */
    public SimTickGenerator(long seed, long[] startPricesScaled) {
        this(seed, startPricesScaled, uniform(startPricesScaled.length, DEFAULT_MAX_STEP_MICRO), false);
    }

    /** Per-instrument start prices and steps, no regimes. */
    public SimTickGenerator(long seed, long[] startPricesScaled, long[] maxStepMicros) {
        this(seed, startPricesScaled, maxStepMicros, false);
    }

    /**
     * @param maxStepMicros  per-tick max step in millionths of price, per instrument.
     * @param regimesEnabled correlated market episodes (trend/vol/shock) on top of the walk.
     */
    public SimTickGenerator(long seed, long[] startPricesScaled, long[] maxStepMicros, boolean regimesEnabled) {
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
        this.regimesEnabled = regimesEnabled;
        this.dwellRoundsLeft = regimesEnabled ? MIN_DWELL_ROUNDS : Integer.MAX_VALUE;
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
        if (regimesEnabled && instrumentIndex == 0) {
            advanceRegime();
        }
        long price = pricesScaled[instrumentIndex];
        long max = maxStepMicros[instrumentIndex];
        // Random step scaled by the regime's vol, plus the regime drift; both proportional
        // to this instrument's own calibrated step so a regime is scale-free.
        long volMax = max * regime.volMultiple();
        long step = random.nextLong(2 * volMax + 1) - volMax;
        long drift = max * regime.driftPerMille() / 1_000L;
        long jump = shockSteps * max;
        long deltaMicro = step + drift + jump;
        // Overflow-safe: price ≤ ~2e10 (a 20k-point future), |delta| ≤ ~3.2e4 → ≤ 6.4e14 << Long.MAX.
        long next = Math.max(MIN_PRICE_SCALED, price + price * deltaMicro / 1_000_000L);
        pricesScaled[instrumentIndex] = next;
        if (++roundCursor >= pricesScaled.length) {
            roundCursor = 0;
            shockSteps = 0; // a shock is a single correlated round
        }
        return next;
    }

    private void advanceRegime() {
        if (--dwellRoundsLeft > 0) {
            return;
        }
        dwellRoundsLeft = MIN_DWELL_ROUNDS + random.nextInt(MAX_DWELL_ROUNDS - MIN_DWELL_ROUNDS);
        if (random.nextDouble() < SHOCK_PROBABILITY) {
            // One-tick correlated jump, then a volatile aftermath.
            long magnitude = SHOCK_MIN_STEPS + random.nextInt(SHOCK_MAX_STEPS - SHOCK_MIN_STEPS);
            shockSteps = random.nextBoolean() ? magnitude : -magnitude;
            regime = MarketRegime.VOLATILE;
            return;
        }
        MarketRegime[] regimes = MarketRegime.values();
        // CALM is twice as likely as each other regime.
        int pick = random.nextInt(regimes.length + 1);
        regime = pick >= regimes.length ? MarketRegime.CALM : regimes[pick];
    }

    /** Current market regime (observability; CALM when regimes are disabled). */
    public MarketRegime regime() {
        return regime;
    }

    /** Sign of the current round's correlated shock jump; 0 when no shock is in flight. */
    public int shockSign() {
        return Long.signum(shockSteps);
    }

    /** Deterministic pseudo-random trade quantity: 1..1000 whole units, scaled. */
    public long nextQuantityScaled() {
        return (random.nextInt(1000) + 1) * 1_000_000L;
    }
}
