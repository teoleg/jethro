package io.jethro.trading.marketdata.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The publisher's metronome (deadline scheduling): work time and timer jitter must NOT stretch
 * the period — that's the classic sleep-after-work bug this class exists to kill.
 */
class TickClockTest {

    private static final long MS = 1_000_000L;

    /** Deterministic fake time: sleeps advance the clock exactly (+ optional jitter), work is
     *  simulated by advancing time between awaitNextTick calls. */
    private static final class FakeTime {
        long now = 1_000_000_000L;
        long jitterPerSleep;
        long sleeps;

        long nanoTime() {
            return now;
        }

        void sleep(long nanos) {
            sleeps++;
            now += nanos + jitterPerSleep; // parkNanos never wakes early here; jitter = late wake
        }
    }

    @Test
    void workAndJitterDoNotStretchThePeriod() {
        FakeTime t = new FakeTime();
        t.jitterPerSleep = MS; // 1ms late wake on every park
        TickClock clock = new TickClock(() -> 100 * MS, t::nanoTime, t::sleep);
        long start = t.now;
        for (int i = 0; i < 100; i++) {
            t.now += 30 * MS; // 30ms of work each tick
            clock.awaitNextTick();
        }
        long elapsed = t.now - start;
        // Naive sleep-after-work would need 100 × (100+30+1)ms = 13.1s. Deadline scheduling
        // absorbs work+jitter: 100 ticks in ~10s (one trailing jitter allowed).
        assertTrue(elapsed <= 100 * 100 * MS + 5 * MS,
                "period stretched: " + elapsed / MS + "ms for 100 ticks");
        assertEquals(0, clock.stats().lateTicks());
        assertEquals(0, clock.stats().resyncs());
    }

    @Test
    void smallOverrunEmitsImmediatelyAndCountsLate() {
        FakeTime t = new FakeTime();
        TickClock clock = new TickClock(() -> 100 * MS, t::nanoTime, t::sleep);
        clock.awaitNextTick();               // sync
        t.now += 250 * MS;                   // one slow tick: 250ms of work on a 100ms interval
        long sleepsBefore = t.sleeps;
        clock.awaitNextTick();               // deadline already passed → no sleep, counted late
        assertEquals(sleepsBefore, t.sleeps, "an overrun tick must emit immediately, not sleep");
        assertEquals(1, clock.stats().lateTicks());
        assertEquals(0, clock.stats().resyncs());
        assertTrue(clock.stats().lagNanos() > 0);
    }

    @Test
    void bigStallResyncsInsteadOfBursting() {
        FakeTime t = new FakeTime();
        TickClock clock = new TickClock(() -> 100 * MS, t::nanoTime, t::sleep);
        clock.awaitNextTick();
        t.now += 3_000 * MS;                 // 3s GC-style stall = 30 missed intervals
        clock.awaitNextTick();               // resync: schedule a fresh deadline, don't replay 30
        assertEquals(1, clock.stats().resyncs());
        long before = t.now;
        clock.awaitNextTick();               // next tick paces normally again
        assertTrue(t.now - before >= 100 * MS, "post-resync tick must wait a full interval");
    }

    @Test
    void liveIntervalChangeTakesEffectNextTick() {
        FakeTime t = new FakeTime();
        long[] interval = {100 * MS};
        TickClock clock = new TickClock(() -> interval[0], t::nanoTime, t::sleep);
        clock.awaitNextTick();
        interval[0] = 20 * MS;               // speed dial ×5
        long before = t.now;
        clock.awaitNextTick();
        assertEquals(20 * MS, t.now - before, "new interval must pace the very next tick");
    }

    @Test
    void resyncForgetsTheScheduleAcrossAPause() {
        FakeTime t = new FakeTime();
        TickClock clock = new TickClock(() -> 100 * MS, t::nanoTime, t::sleep);
        clock.awaitNextTick();
        t.now += 60_000 * MS;                // paused a minute
        clock.resync();
        clock.awaitNextTick();
        assertEquals(0, clock.stats().resyncs(), "a resumed pause is not an overrun");
        assertEquals(0, clock.stats().lateTicks());
    }
}
