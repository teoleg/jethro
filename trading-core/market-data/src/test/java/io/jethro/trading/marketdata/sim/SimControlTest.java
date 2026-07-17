package io.jethro.trading.marketdata.sim;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the sim control handle (ADR-0031): identity defaults, clamping, one-shot
 *  nudge accumulation, the active-dial flag, and reset. */
class SimControlTest {

    private static SimControl control() {
        return new SimControl(10_000_000L, List.of("ES", "AAPL"));
    }

    @Test
    void defaultsAreTheIdentityAndReportInactive() {
        SimControl c = control();
        assertEquals(10_000_000L, c.effectiveTickIntervalNanos());
        assertFalse(c.paused());
        assertEquals(1.0, c.speedMultiplier());
        assertEquals(0.0, c.driftBiasFor("ES"));
        assertEquals(1.0, c.volMultiplierFor("ES"));
        assertEquals(1.0, c.volumeScaleFor("AAPL"));
        assertEquals(0.0, c.consumeNudge(0));
        assertEquals(SimControl.NO_RESEED, c.consumeReseed());
        assertFalse(c.anyDialActive(), "untouched panel must read as inactive (seeded tape)");
    }

    @Test
    void speedScalesTheTickInterval() {
        SimControl c = control();
        c.setSpeedMultiplier(2.0);
        assertEquals(5_000_000L, c.effectiveTickIntervalNanos());
        c.setSpeedMultiplier(0.5);
        assertEquals(20_000_000L, c.effectiveTickIntervalNanos());
    }

    @Test
    void dialsClampToSafeRanges() {
        SimControl c = control();
        c.setSpeedMultiplier(10_000);          // clamps to the max
        assertTrue(c.speedMultiplier() <= 50.0);
        c.setVolMultiplier("ES", -3);           // no negative vol
        assertEquals(0.0, c.volMultiplierFor("ES"));
        c.setVolumeScale("ES", 9_999);
        assertTrue(c.volumeScaleFor("ES") <= 100.0);
        c.setDriftBias("ES", 5.0);              // clamps to a per-tick sane band
        assertTrue(Math.abs(c.driftBiasFor("ES")) <= 0.01);
    }

    @Test
    void nudgeIsConsumedOnceAndAccumulatesWithinATick() {
        SimControl c = control();
        c.nudge("ES", 0.02);
        c.nudge("ES", 0.03);
        // (1.02)(1.03) - 1 = 0.0506 compounded
        assertEquals(0.0506, c.consumeNudge(0), 1e-9);
        assertEquals(0.0, c.consumeNudge(0), "nudge is one-shot");
    }

    @Test
    void reseedIsConsumedOnce() {
        SimControl c = control();
        c.reseed(99);
        assertEquals(99, c.consumeReseed());
        assertEquals(SimControl.NO_RESEED, c.consumeReseed());
    }

    @Test
    void anyDialActiveTracksEachControl() {
        SimControl c = control();
        c.setDriftBias("AAPL", 0.001);
        assertTrue(c.anyDialActive());
        c.resetAll();
        assertFalse(c.anyDialActive());
        c.overrideRegime(MarketRegime.RISK_OFF);
        assertTrue(c.anyDialActive());
    }

    @Test
    void unknownInstrumentIsRejected() {
        SimControl c = control();
        assertThrows(IllegalArgumentException.class, () -> c.setDriftBias("NOPE", 0.001));
    }
}
