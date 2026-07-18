package io.jethro.trading.marketdata.sim;

import java.util.List;
import java.util.SplittableRandom;

/**
 * History-anchored market simulator (ADR-0032): a <b>stationary block bootstrap</b> (Politis–
 * Romano) over a {@link HistoricalSnapshot}'s real daily return+volume vectors. Each step either
 * continues the current historical block (prob {@code 1 − 1/meanBlock}) or jumps to a random new
 * day (prob {@code 1/meanBlock}); because a whole cross-sectional day is drawn at once, the real
 * correlations, fat tails and vol-clustering of the source history carry into a novel, seedable
 * path — a far better tape to develop models against than a parametric process.
 *
 * <p>Exposes the same shape the sim adapter needs (advance, price, volume) and reads the live
 * {@link SimControl} dials (ADR-0031) — per-instrument vol/drift, one-shot nudges, reseed — so an
 * untouched panel reproduces the seeded bootstrap exactly (defaults are the identity and never
 * draw from the RNG). Prices evolve in {@code double}; the mark is scaled-long at emission.
 */
public final class HistoricalBootstrapSimulator {

    private static final long MIN_PRICE_SCALED = 10_000L; // 0.01 floor — no zero/negative marks

    private final String[] ids;
    private final double[][] returns;     // [instrument][step], real daily log-returns
    private final long[][] stepVolume;    // [instrument][step], the arrival day's real volume
    private final int steps;
    private final double newBlockProb;    // 1 / meanBlockLength
    private final SimControl control;
    private final long[] startPricesScaled;
    private final double[] price;

    private SplittableRandom random;      // non-final: a live reseed swaps it
    private int cursor = -1;              // current index into the historical series

    public HistoricalBootstrapSimulator(long seed, HistoricalSnapshot snapshot, List<String> instrumentIds,
                                        long[] startPricesScaled, double meanBlockLength, SimControl control) {
        if (instrumentIds.size() != startPricesScaled.length) {
            throw new IllegalArgumentException("start prices must align with instruments");
        }
        if (meanBlockLength < 1) {
            throw new IllegalArgumentException("mean block length must be >= 1");
        }
        this.ids = instrumentIds.toArray(String[]::new);
        this.control = control;
        this.startPricesScaled = startPricesScaled.clone();
        this.newBlockProb = 1.0 / meanBlockLength;
        this.random = new SplittableRandom(seed);

        // Align the snapshot's instruments to the ids the adapter asked for; a missing instrument
        // gets a flat (zero-return) series so the universe still emits, logged by the caller.
        double[][] snapReturns = snapshot.logReturns();
        this.steps = snapshot.days() - 1;
        this.returns = new double[ids.length][steps];
        this.stepVolume = new long[ids.length][steps];
        List<String> snapIds = snapshot.instrumentIds();
        this.price = new double[ids.length];
        for (int i = 0; i < ids.length; i++) {
            int si = snapIds.indexOf(ids[i]);
            for (int t = 0; t < steps; t++) {
                returns[i][t] = si >= 0 ? snapReturns[si][t] : 0.0;
                stepVolume[i][t] = si >= 0 ? snapshot.volume(si, t + 1) : 0L;
            }
            price[i] = startPricesScaled[i] / 1_000_000.0;
        }
    }

    /** Advances one tick: applies control actions, moves the bootstrap cursor, evolves prices. */
    public void nextTick() {
        applyControlActions();
        advanceCursor();
        for (int i = 0; i < ids.length; i++) {
            // Live dials (ADR-0031): vol scales this name's realized move, drift adds a per-tick
            // bias. Defaults (×1, +0) leave the historical return — and the RNG stream — untouched.
            double r = returns[i][cursor] * control.volMultiplier(i) + control.driftBias(i);
            price[i] = price[i] * Math.exp(r);
        }
    }

    private void advanceCursor() {
        if (cursor < 0 || random.nextDouble() < newBlockProb) {
            cursor = random.nextInt(steps);   // start a fresh block at a random historical day
        } else {
            cursor = (cursor + 1) % steps;    // continue the block (wrapping the tape)
        }
    }

    private void applyControlActions() {
        long reseed = control.consumeReseed();
        if (reseed != SimControl.NO_RESEED) {
            random = new SplittableRandom(reseed);
            cursor = -1;
            for (int i = 0; i < ids.length; i++) {
                price[i] = startPricesScaled[i] / 1_000_000.0;
            }
        }
        for (int i = 0; i < ids.length; i++) {
            double nudge = control.consumeNudge(i);
            if (nudge != 0.0) {
                price[i] = Math.max(MIN_PRICE_SCALED / 1_000_000.0, price[i] * (1.0 + nudge));
            }
        }
    }

    public long priceScaled(int instrumentIndex) {
        return Math.max(MIN_PRICE_SCALED, Math.round(price[instrumentIndex] * 1_000_000));
    }

    /** The real (bootstrapped) daily volume for the current step — the adapter divides by
     *  ticks/day for a per-tick print size. Zero for an instrument absent from the snapshot. */
    public long currentDailyVolume(int instrumentIndex) {
        return cursor < 0 ? 0L : stepVolume[instrumentIndex][cursor];
    }
}
