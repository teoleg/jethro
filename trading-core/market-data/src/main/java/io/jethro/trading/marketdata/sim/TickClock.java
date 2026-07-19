package io.jethro.trading.marketdata.sim;

import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;

/**
 * Absolute-deadline pacing for the sim feed thread — the publisher's metronome.
 *
 * <p><b>Why not {@code sleep(interval)} after the work?</b> That naive loop's real period is
 * {@code interval + workTime + timerJitter}: the achieved rate is always below nominal, the error
 * is load-dependent, and it compounds — over a minute at 10/s with 3ms of work+jitter you emit
 * ~582 ticks, not 600, and nobody can see it. Since each tick advances a fixed slice of market
 * time, rate drift silently bends market-time-per-wall-second too.
 *
 * <p>This clock schedules against <b>absolute deadlines</b>: {@code deadline += interval} every
 * tick, sleep only the remaining time. Work time and jitter then cancel instead of accumulating —
 * the long-run rate equals the target exactly, as long as the box can keep up.
 *
 * <p><b>Overrun policy: skip, never burst.</b> If the thread falls behind by more than
 * {@link #RESYNC_THRESHOLD_INTERVALS} intervals (GC pause, CPU starvation, a huge speed-up on a
 * small box), the deadline resyncs to "now" and the miss is COUNTED ({@link Stats#resyncs},
 * {@link Stats#lagNanos}) rather than repaid — replaying 50 missed ticks back-to-back would print
 * a fake microburst at one timestamp, which is worse than honestly running slow. Small misses
 * (deadline passed but under the threshold) emit immediately and are counted as {@link Stats#lateTicks}.
 *
 * <p>The achieved rate is measured (1s-window count, EWMA-smoothed) so the UI can show
 * "target 10.0/s · achieving 10.0/s" — and, at an over-ambitious speed dial, the truth instead.
 * Wall-clock never feeds the price path (determinism, ADR-0009): this class only decides WHEN to
 * emit, never WHAT.
 */
public final class TickClock {

    static final int RESYNC_THRESHOLD_INTERVALS = 4;
    private static final double RATE_EWMA_ALPHA = 0.3; // ~3s to converge on a 1s window

    /** Publisher telemetry: counted, not estimated. */
    public record Stats(long ticks, long lateTicks, long resyncs, long lagNanos,
                        double targetTicksPerSecond, double achievedTicksPerSecond) {
    }

    private final LongSupplier intervalNanos; // re-read every tick: the speed dial moves it live
    private final LongSupplier nanoTime;
    private final java.util.function.LongConsumer sleeper;

    private long deadline = Long.MIN_VALUE;   // MIN_VALUE = unsynced (first tick / after pause)
    private long ticks;
    private long lateTicks;
    private long resyncs;
    private long lagNanos;
    // achieved-rate measurement: count ticks per 1s wall window, EWMA the window rates
    private long windowStart = Long.MIN_VALUE;
    private long windowTicks;
    private volatile double achievedRate;

    public TickClock(LongSupplier intervalNanos) {
        this(intervalNanos, System::nanoTime, LockSupport::parkNanos);
    }

    /** Test seam: injected time + sleeper so the deadline math is provable without real waiting. */
    TickClock(LongSupplier intervalNanos, LongSupplier nanoTime, java.util.function.LongConsumer sleeper) {
        this.intervalNanos = intervalNanos;
        this.nanoTime = nanoTime;
        this.sleeper = sleeper;
    }

    /**
     * Blocks until the next tick is due, then returns. Call once per loop iteration, AFTER the
     * tick's work — the deadline was fixed before the work started, so work time doesn't stretch
     * the period.
     */
    public void awaitNextTick() {
        long interval = Math.max(1, intervalNanos.getAsLong());
        long now = nanoTime.getAsLong();
        if (deadline == Long.MIN_VALUE) {
            deadline = now + interval; // first tick after start/resync: full interval from now
        } else {
            deadline += interval;
        }
        long behind = now - deadline;
        if (behind > 0) {
            // Missed the deadline. Small miss: emit immediately, count it. Big miss: resync so we
            // never replay a burst of stale ticks at one timestamp.
            if (behind > RESYNC_THRESHOLD_INTERVALS * interval) {
                resyncs++;
                lagNanos += behind;
                deadline = now + interval;
                sleepUntil(deadline);
            } else {
                lateTicks++;
                lagNanos += behind;
            }
        } else {
            sleepUntil(deadline);
        }
        countTick();
    }

    /** Forget the schedule (pause/resume, engine swap): the next tick starts a fresh deadline. */
    public void resync() {
        deadline = Long.MIN_VALUE;
    }

    public Stats stats() {
        long interval = Math.max(1, intervalNanos.getAsLong());
        return new Stats(ticks, lateTicks, resyncs, lagNanos,
                1_000_000_000.0 / interval, achievedRate);
    }

    private void sleepUntil(long target) {
        long remaining;
        while ((remaining = target - nanoTime.getAsLong()) > 0) {
            sleeper.accept(remaining); // parkNanos may wake early/spuriously — loop on the deadline
        }
    }

    private void countTick() {
        ticks++;
        long now = nanoTime.getAsLong();
        if (windowStart == Long.MIN_VALUE) {
            windowStart = now;
        }
        windowTicks++;
        long elapsed = now - windowStart;
        if (elapsed >= 1_000_000_000L) {
            double windowRate = windowTicks * 1_000_000_000.0 / elapsed;
            achievedRate = achievedRate == 0.0
                    ? windowRate
                    : achievedRate + RATE_EWMA_ALPHA * (windowRate - achievedRate);
            windowStart = now;
            windowTicks = 0;
        }
    }
}
