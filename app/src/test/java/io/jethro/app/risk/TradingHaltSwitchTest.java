package io.jethro.app.risk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Breaker switch semantics (ADR-0027): first trip's reason stands; reset is explicit. */
class TradingHaltSwitchTest {

    @Test
    void firstTripWinsUntilReset() {
        var s = new TradingHaltSwitch();
        assertFalse(s.isHalted());
        s.trip("drawdown 60000 breached cap 50000");
        s.trip("a later reason must not overwrite the original");
        assertTrue(s.isHalted());
        assertEquals("drawdown 60000 breached cap 50000", s.current().reason());
    }

    @Test
    void resetClearsAndReportsWhetherItDidAnything() {
        var s = new TradingHaltSwitch();
        assertFalse(s.reset(), "resetting an armed (untripped) breaker is a no-op");
        s.trip("x");
        assertTrue(s.reset(), "cleared a real halt");
        assertFalse(s.isHalted());
    }
}
