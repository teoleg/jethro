package io.jethro.app.fusion;

import io.jethro.trading.algo.strategy.EwmacTrendForecaster;
import io.jethro.trading.algo.strategy.RangeReversionForecaster;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0113: a continuous sensor advances on PRINTS, not on cycles. The mark cache republishes a
 * last-value price whether or not the tape printed, so a sensor driven off the cycle clock eats
 * fabricated zero-return steps once a name stops trading — and its {@code stale} flag cannot see that,
 * because that flag is a warm-load marker, false forever after the first live tick.
 */
class PrintClockTest {

    private static final String NAME = "AAPL";
    private static final long T0 = 1_700_000_000_000L;
    /** The sensors' evaluation cadence in the test, in the provider clock's units. */
    private static final long STEP_MILLIS = 12_000L;

    // ---- the rule itself ----

    @Test
    void admitsTheFirstSightAndThenOnlyStrictlyNewerPrints() {
        PrintClock clock = new PrintClock();

        assertThat(clock.advanced(NAME, Instant.ofEpochMilli(T0))).isTrue();       // first sight
        assertThat(clock.advanced(NAME, Instant.ofEpochMilli(T0))).isFalse();      // the same print again
        assertThat(clock.advanced(NAME, Instant.ofEpochMilli(T0 - 1))).isFalse();  // a laggard source
        assertThat(clock.advanced(NAME, Instant.ofEpochMilli(T0 + 1))).isTrue();   // the tape printed
        assertThat(clock.advanced(NAME, Instant.ofEpochMilli(T0 + 1))).isFalse();
    }

    @Test
    void tracksEachInstrumentOnItsOwnClock() {
        PrintClock clock = new PrintClock();
        clock.advanced(NAME, Instant.ofEpochMilli(T0 + 60_000L));

        // A name whose tape is running is unaffected by a name whose tape has stopped, and vice versa.
        assertThat(clock.advanced("EURUSD", Instant.ofEpochMilli(T0))).isTrue();
        assertThat(clock.advanced(NAME, Instant.ofEpochMilli(T0))).isFalse();
        assertThat(clock.trackedInstruments()).isEqualTo(2);
    }

    @Test
    void admitsAMarkWithNoProviderClockRatherThanSilencingTheSensor() {
        PrintClock clock = new PrintClock();

        assertThat(clock.advanced(NAME, null)).isTrue();
        assertThat(clock.advanced(NAME, null)).isTrue();
        assertThat(clock.advanced(null, Instant.ofEpochMilli(T0))).isFalse();
    }

    // ---- what it is for: a dead tape must not move a sensor ----

    /**
     * The invariant the gate buys, stated exactly: after any number of cycles on a tape that has stopped
     * printing, the sensor is bit-for-bit where its last real print left it. Without the gate the same
     * sequence walks the reversion sensor's Donchian window flat and it stops being warm at all — it
     * un-calibrates itself on prices that never happened.
     */
    @Test
    void aFrozenTapeLeavesTheReversionSensorExactlyWhereTheLastPrintLeftIt() {
        var params = new RangeReversionForecaster.Params(10, 20);
        var gated = new RangeReversionForecaster(params);
        var ungated = new RangeReversionForecaster(params);
        PrintClock clock = new PrintClock();
        List<BigDecimal> tape = zigzag(120);

        for (int i = 0; i < tape.size(); i++) {
            if (clock.advanced(NAME, Instant.ofEpochMilli(T0 + i * STEP_MILLIS))) {
                gated.update(NAME, tape.get(i));
            }
            ungated.update(NAME, tape.get(i));
        }
        var atLastPrint = gated.readingFor(NAME);
        assertThat(atLastPrint.warm()).isTrue();
        assertThat(atLastPrint.score()).isNotEqualTo(0.0);

        // The tape stops. The mark cache keeps republishing the same last price, every one of them
        // carrying the same provider timestamp — 300 cycles of it (an hour at a 12s cadence).
        long frozenAt = T0 + (tape.size() - 1) * STEP_MILLIS;
        BigDecimal lastPrint = tape.get(tape.size() - 1);
        for (int i = 0; i < 300; i++) {
            if (clock.advanced(NAME, Instant.ofEpochMilli(frozenAt))) {
                gated.update(NAME, lastPrint);
            }
            ungated.update(NAME, lastPrint);
        }

        assertThat(gated.readingFor(NAME)).isEqualTo(atLastPrint);
        assertThat(ungated.readingFor(NAME).warm()).isFalse();
    }

    /**
     * The expensive half, on the trend sensor: its score is divided by an EWMA of its own typical
     * magnitude, and a run of repeated prices is a run of zero-magnitude readings that decays that
     * denominator toward zero. So when the tape RESUMES, an ordinary move is divided by a near-zero
     * scale and the name reports an extreme conviction — the ADR-0066 pin, reached from the other side.
     * The gated sensor reads the same resumed prints on the calibration it had before the halt.
     */
    @Test
    void aFrozenTapeInflatesTheTrendSensorsNextRealReading() {
        var params = new EwmacTrendForecaster.Params(4, 16, 32);
        var gated = new EwmacTrendForecaster(params);
        var ungated = new EwmacTrendForecaster(params);
        // The counterfactual: the same sensor on the same prints, in a session that never halted.
        var control = new EwmacTrendForecaster(params);
        PrintClock clock = new PrintClock();
        List<BigDecimal> tape = zigzag(160);

        for (int i = 0; i < tape.size(); i++) {
            if (clock.advanced(NAME, Instant.ofEpochMilli(T0 + i * STEP_MILLIS))) {
                gated.update(NAME, tape.get(i));
            }
            ungated.update(NAME, tape.get(i));
            control.update(NAME, tape.get(i));
        }
        assertThat(gated.readingFor(NAME).warm()).isTrue();

        long frozenAt = T0 + (tape.size() - 1) * STEP_MILLIS;
        BigDecimal lastPrint = tape.get(tape.size() - 1);
        for (int f = 0; f < 300; f++) {
            if (clock.advanced(NAME, Instant.ofEpochMilli(frozenAt))) {
                gated.update(NAME, lastPrint);
            }
            ungated.update(NAME, lastPrint); // the control simply is not there for the halt
        }

        // The session reopens and the tape prints normally again. The loudest reading over the reopen
        // is what matters: a conviction is acted on when it is published, not when it has decayed back.
        List<BigDecimal> resumed = zigzag(180).subList(160, 180);
        long resumeAt = frozenAt + 3_600_000L;
        double gatedPeak = 0.0;
        double ungatedPeak = 0.0;
        for (int r = 0; r < resumed.size(); r++) {
            if (clock.advanced(NAME, Instant.ofEpochMilli(resumeAt + r * STEP_MILLIS))) {
                gated.update(NAME, resumed.get(r));
            }
            ungated.update(NAME, resumed.get(r));
            control.update(NAME, resumed.get(r));
            gatedPeak = Math.max(gatedPeak, Math.abs(gated.readingFor(NAME).score()));
            ungatedPeak = Math.max(ungatedPeak, Math.abs(ungated.readingFor(NAME).score()));
        }

        // The design claim, with no tuned constant in it: the halt is INVISIBLE to a gated sensor —
        // it reads the reopen on exactly the calibration a session that never stopped would have had.
        assertThat(gated.readingFor(NAME)).isEqualTo(control.readingFor(NAME));
        // And the halt is emphatically not invisible without the gate. The bound is deliberately loose
        // (the measured factor is larger): what is asserted is the direction and the order, not a fixture.
        assertThat(gatedPeak).isGreaterThan(0.0);
        assertThat(ungatedPeak).isGreaterThan(gatedPeak * 3.0);
    }

    /**
     * A deterministic exact-decimal price series with a real range and a slow drift, so the Donchian
     * channel and the efficiency ratio are both non-degenerate. A triangle wave rather than anything
     * random: a fixed seed still hides which sequence a failure is about.
     */
    private static List<BigDecimal> zigzag(int count) {
        List<BigDecimal> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int phase = i % 14;
            int step = phase < 7 ? phase : 14 - phase; // 0,1,…,6,7,6,…,1
            long ticks = step * 3L + i / 14L;          // the triangle plus one tick of drift per cycle
            out.add(new BigDecimal("100").add(BigDecimal.valueOf(ticks).movePointLeft(2)));
        }
        return out;
    }
}
