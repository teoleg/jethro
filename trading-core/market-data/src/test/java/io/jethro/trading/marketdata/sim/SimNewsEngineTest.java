package io.jethro.trading.marketdata.sim;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The sim news generator (ADR-0034): deterministic event stream, and each event actually shocks
 *  the tape through SimControl. */
class SimNewsEngineTest {

    private static final List<String> IDS = List.of("ES", "AAPL", "EURUSD");

    @Test
    void sameSeedSameNewsStream() {
        var a = new SimNewsEngine(42, IDS, new SimControl(1, IDS), 1.0, 5);
        var b = new SimNewsEngine(42, IDS, new SimControl(1, IDS), 1.0, 5);
        for (int t = 0; t < 12; t++) {
            a.maybeFire(t);
            b.maybeFire(t);
        }
        var ra = a.recent();
        var rb = b.recent();
        assertFalse(ra.isEmpty(), "probability 1.0 fires every tick");
        assertEquals(ra.size(), rb.size());
        for (int i = 0; i < ra.size(); i++) {
            assertEquals(ra.get(i).instrumentId(), rb.get(i).instrumentId());
            assertEquals(ra.get(i).sign(), rb.get(i).sign());
            assertEquals(ra.get(i).headline(), rb.get(i).headline());
        }
    }

    @Test
    void firingShocksTheTape() {
        var control = new SimControl(1, IDS);
        var engine = new SimNewsEngine(7, IDS, control, 1.0, 5);
        engine.maybeFire(0);
        boolean anyShock = false;
        for (int i = 0; i < IDS.size(); i++) {
            if (control.volumeScale(i) > 1.0 || control.driftBias(i) != 0.0) {
                anyShock = true;
            }
        }
        assertTrue(anyShock, "a fired event must apply a shock to its instrument");
        assertEquals(1, engine.recent().size());
    }

    @Test
    void zeroProbabilityNeverFires() {
        var engine = new SimNewsEngine(1, IDS, new SimControl(1, IDS), 0.0, 5);
        for (int t = 0; t < 200; t++) {
            engine.maybeFire(t);
        }
        assertTrue(engine.recent().isEmpty());
    }
}
