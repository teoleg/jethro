package io.jethro.trading.marketdata.sim;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** News shocks on SimControl (ADR-0034): a repricing jump, a decaying momentum drift, and a
 *  decaying volume surge that expire over the horizon; untouched dials are unaffected. */
class SimNewsShockTest {

    private static SimControl control() {
        return new SimControl(1, List.of("ES", "AAPL"));
    }

    @Test
    void bullishShockJumpsMomentumAndVolumeThenDecays() {
        var c = control();
        int es = 0;
        c.fireNewsShock("ES", 1, 0.02, 4);

        assertEquals(0.02, c.consumeNudge(es), 1e-9, "the repricing jump is queued as a one-shot nudge");
        assertTrue(c.driftBias(es) > 0, "bullish momentum drift while the shock is active");
        double vol0 = c.volumeScale(es);
        assertTrue(vol0 > 1.0, "volume surges on the news");

        c.onTick();
        double vol1 = c.volumeScale(es);
        assertTrue(vol1 > 1.0 && vol1 < vol0, "the surge decays each tick");

        c.onTick();
        c.onTick();
        c.onTick(); // 4 ticks total → expired
        assertEquals(0.0, c.driftBias(es), 1e-12, "momentum is gone after the horizon");
        assertEquals(1.0, c.volumeScale(es), 1e-12, "volume back to baseline after the horizon");
    }

    @Test
    void bearishShockPushesDown() {
        var c = control();
        c.fireNewsShock("ES", -1, 0.015, 5);
        assertTrue(c.consumeNudge(0) < 0, "bearish jump is negative");
        assertTrue(c.driftBias(0) < 0, "bearish momentum is negative");
    }

    @Test
    void unknownInstrumentIsANoOp() {
        var c = control();
        c.fireNewsShock("NOPE", 1, 0.02, 4); // must not throw
        assertEquals(0.0, c.driftBias(0));
        assertEquals(1.0, c.volumeScale(0));
    }

    @Test
    void shockDoesNotLeakToOtherInstruments() {
        var c = control();
        c.fireNewsShock("ES", 1, 0.02, 4);
        assertEquals(0.0, c.driftBias(1), "AAPL is untouched by an ES shock");
        assertEquals(1.0, c.volumeScale(1));
    }
}
