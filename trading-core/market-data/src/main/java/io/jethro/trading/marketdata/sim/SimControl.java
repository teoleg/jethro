package io.jethro.trading.marketdata.sim;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Live control surface over the correlated sim (ADR-0031): the mutable dials the tick
 * generator reads each tick so an operator can stage scenarios interactively — speed the
 * feed up, pause it, push a name's price, add drift or idiosyncratic vol to one instrument,
 * force a regime — and watch the models react without a restart.
 *
 * <p><b>Sim-only.</b> A {@code SimControl} exists only when the correlated sim adapter is the
 * feed; the REST/UI surface that mutates it is hard-gated to {@code feedMode == SIM}
 * (ADR-0029), so no dial can ever perturb a live or replay tape.
 *
 * <p><b>Determinism preserved when untouched.</b> Every dial defaults to the identity value
 * (speed ×1, no pause, no regime override, per-instrument drift 0, vol ×1, volume ×1), and the
 * hot path reads those defaults without drawing from the RNG — so an unmoved panel yields the
 * exact same seeded tape as before this class existed (ADR-0009).
 *
 * <p><b>Thread-safety.</b> The REST thread mutates; the {@code sim-feed} thread reads once per
 * tick. Globals are {@code volatile}; per-instrument dials sit in {@link AtomicLongArray}s
 * (double bits) — element-atomic, allocation-free reads, no torn values. These are statistical
 * parameters (like the calibrated vols), never money, so {@code double} is correct here.
 */
public final class SimControl {

    /** Sentinel for "no reseed pending" — a real reseed always carries an explicit seed. */
    public static final long NO_RESEED = Long.MIN_VALUE;

    private static final double MIN_SPEED = 0.05;   // 20× slower than baseline
    private static final double MAX_SPEED = 50.0;   // 50× faster than baseline
    private static final long PAUSE_POLL_NANOS = 20_000_000L; // 20 ms — responsive un-pause

    private final long baseTickIntervalNanos;
    private final String[] ids;                 // controllable (factor-priced) instruments
    private final Map<String, Integer> indexById;

    private volatile double speedMultiplier = 1.0;
    private volatile boolean paused = false;
    private volatile MarketRegime regimeOverride = null; // null = AUTO (seeded Markov chain)
    private final AtomicLong pendingReseed = new AtomicLong(NO_RESEED);

    private final AtomicLongArray driftBiasBits;  // additive per-tick log-drift, default 0.0
    private final AtomicLongArray volMultBits;    // idiosyncratic-vol multiple, default 1.0
    private final AtomicLongArray volumeScaleBits; // trade-quantity multiple, default 1.0
    private final AtomicLongArray pendingNudgeBits; // one-shot price fraction, consumed per tick

    public SimControl(long baseTickIntervalNanos, List<String> controllableIds) {
        this.baseTickIntervalNanos = baseTickIntervalNanos;
        this.ids = controllableIds.toArray(String[]::new);
        this.indexById = new LinkedHashMap<>();
        for (int i = 0; i < ids.length; i++) {
            indexById.put(ids[i], i);
        }
        int n = ids.length;
        this.driftBiasBits = new AtomicLongArray(n);
        this.volMultBits = bitsArray(n, 1.0);
        this.volumeScaleBits = bitsArray(n, 1.0);
        this.pendingNudgeBits = new AtomicLongArray(n);
    }

    private static AtomicLongArray bitsArray(int n, double value) {
        AtomicLongArray a = new AtomicLongArray(n);
        long bits = Double.doubleToRawLongBits(value);
        for (int i = 0; i < n; i++) {
            a.set(i, bits);
        }
        return a;
    }

    // ---- hot-path reads (sim-feed thread, once per tick) ----

    /** Interval to park between ticks, honouring the live speed dial (never below 1 ms). */
    public long effectiveTickIntervalNanos() {
        long scaled = (long) (baseTickIntervalNanos / speedMultiplier);
        return Math.max(1_000_000L, scaled);
    }

    public boolean paused() {
        return paused;
    }

    /** Poll interval while paused — long enough to idle, short enough to un-pause promptly. */
    public long pausePollNanos() {
        return PAUSE_POLL_NANOS;
    }

    /** Forced regime, or {@code null} for the normal seeded Markov chain. */
    public MarketRegime regimeOverride() {
        return regimeOverride;
    }

    public double driftBias(int index) {
        return Double.longBitsToDouble(driftBiasBits.get(index));
    }

    public double volMultiplier(int index) {
        return Double.longBitsToDouble(volMultBits.get(index));
    }

    public double volumeScale(int index) {
        return Double.longBitsToDouble(volumeScaleBits.get(index));
    }

    /** Consumes any queued one-shot price nudge for {@code index} (fraction; 0 if none). */
    public double consumeNudge(int index) {
        long bits = pendingNudgeBits.getAndSet(index, 0L);
        return bits == 0L ? 0.0 : Double.longBitsToDouble(bits);
    }

    /** Consumes a pending reseed request, returning its seed or {@link #NO_RESEED}. */
    public long consumeReseed() {
        return pendingReseed.getAndSet(NO_RESEED);
    }

    // ---- REST-facing mutations (control thread) ----

    public void setSpeedMultiplier(double multiplier) {
        this.speedMultiplier = clamp(multiplier, MIN_SPEED, MAX_SPEED);
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    /** Force a regime, or pass {@code null} to hand control back to the seeded Markov chain. */
    public void overrideRegime(MarketRegime regime) {
        this.regimeOverride = regime;
    }

    public void reseed(long seed) {
        pendingReseed.set(seed);
    }

    /** Queues a one-shot multiplicative price bump (e.g. +0.02 = +2%) for the next tick. */
    public void nudge(String id, double fraction) {
        int idx = requireIndex(id);
        double clamped = clamp(fraction, -0.9, 5.0); // never zero/negative price, cap a fat-finger
        // Accumulate if two nudges land in the same tick, compounding multiplicatively.
        long prev;
        long next;
        do {
            prev = pendingNudgeBits.get(idx);
            double prevFraction = prev == 0L ? 0.0 : Double.longBitsToDouble(prev);
            double combined = (1.0 + prevFraction) * (1.0 + clamped) - 1.0;
            next = Double.doubleToRawLongBits(combined);
        } while (!pendingNudgeBits.compareAndSet(idx, prev, next));
    }

    public void setDriftBias(String id, double perTickLogDrift) {
        driftBiasBits.set(requireIndex(id), Double.doubleToRawLongBits(clamp(perTickLogDrift, -0.01, 0.01)));
    }

    public void setVolMultiplier(String id, double multiple) {
        volMultBits.set(requireIndex(id), Double.doubleToRawLongBits(clamp(multiple, 0.0, 20.0)));
    }

    public void setVolumeScale(String id, double multiple) {
        volumeScaleBits.set(requireIndex(id), Double.doubleToRawLongBits(clamp(multiple, 0.0, 100.0)));
    }

    /** Resets every dial to its identity value — the panel's "back to seeded config" button. */
    public void resetAll() {
        speedMultiplier = 1.0;
        paused = false;
        regimeOverride = null;
        for (int i = 0; i < ids.length; i++) {
            driftBiasBits.set(i, 0L);
            volMultBits.set(i, Double.doubleToRawLongBits(1.0));
            volumeScaleBits.set(i, Double.doubleToRawLongBits(1.0));
            pendingNudgeBits.set(i, 0L);
        }
    }

    // ---- introspection (status endpoint / labelling) ----

    public List<String> controllableIds() {
        return List.of(ids);
    }

    public double speedMultiplier() {
        return speedMultiplier;
    }

    public double driftBiasFor(String id) {
        return driftBias(requireIndex(id));
    }

    public double volMultiplierFor(String id) {
        return volMultiplier(requireIndex(id));
    }

    public double volumeScaleFor(String id) {
        return volumeScale(requireIndex(id));
    }

    /** True when any dial deviates from its identity value — used to LABEL a panel-driven
     *  session so a screenshot isn't mistaken for organic seeded sim output (ADR-0031). */
    public boolean anyDialActive() {
        if (speedMultiplier != 1.0 || paused || regimeOverride != null) {
            return true;
        }
        for (int i = 0; i < ids.length; i++) {
            if (driftBias(i) != 0.0 || volMultiplier(i) != 1.0 || volumeScale(i) != 1.0) {
                return true;
            }
        }
        return false;
    }

    private int requireIndex(String id) {
        Integer idx = indexById.get(id);
        if (idx == null) {
            throw new IllegalArgumentException("not a controllable sim instrument: " + id);
        }
        return idx;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
