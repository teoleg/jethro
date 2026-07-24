package io.jethro.app.signal;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Exact-value tests for the ADR-0055 phase-1 signal scoring: directional return, WIN/LOSS/FLAT, and
 *  the rolling hit-rate (FLATs are no bet and don't count against it). */
class SignalScoringTest {

    private static final double FLAT_BPS = 10; // 0.10% noise floor

    @Test
    void directionalReturnFollowsTheCall() {
        // Long 100 → 110 = +10%. Short the same move = −10%. Exact.
        assertEquals(0.10, SignalScoring.directionalReturn(1, bd(100), bd(110)), 1e-12);
        assertEquals(-0.10, SignalScoring.directionalReturn(-1, bd(100), bd(110)), 1e-12);
        assertEquals(0.0, SignalScoring.directionalReturn(0, bd(100), bd(110)), 1e-12);
        assertEquals(0.0, SignalScoring.directionalReturn(1, bd(0), bd(110)), 1e-12, "non-positive entry → 0");
    }

    @Test
    void outcomeThresholdsAtTheNoiseFloor() {
        assertEquals(SignalScoring.Outcome.WIN, SignalScoring.outcome(0.0011, FLAT_BPS));  // +11 bps > 10
        assertEquals(SignalScoring.Outcome.LOSS, SignalScoring.outcome(-0.0011, FLAT_BPS)); // −11 bps
        assertEquals(SignalScoring.Outcome.FLAT, SignalScoring.outcome(0.0005, FLAT_BPS));  // +5 bps < 10
    }

    @Test
    void hitRateExcludesFlatsAndAveragesReturns() {
        // Returns: +2%, +2%, −2%, +0.05% (flat). 2 wins, 1 loss, 1 flat.
        List<Double> rets = List.of(0.02, 0.02, -0.02, 0.0005);
        SignalScoring.Stats s = SignalScoring.aggregate("momentum", rets, FLAT_BPS, 3);
        assertEquals(4, s.resolved());
        assertEquals(2, s.wins());
        assertEquals(1, s.losses());
        assertEquals(1, s.flats());
        assertEquals(3, s.open());
        // hit-rate = wins / (wins+losses) = 2/3, FLAT excluded.
        assertEquals(2.0 / 3.0, s.hitRate(), 1e-12);
        // average return over ALL resolved = (0.02+0.02−0.02+0.0005)/4 = 0.010125 → 101.25 bps.
        assertEquals(101.25, s.avgReturnBps(), 1e-9);
    }

    @Test
    void emptyIsZeroedNotNaN() {
        SignalScoring.Stats s = SignalScoring.aggregate("social", List.of(), FLAT_BPS, 0);
        assertEquals(0, s.resolved());
        assertEquals(0.0, s.hitRate(), 1e-12);
        assertEquals(0.0, s.avgReturnBps(), 1e-12);
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v);
    }
}
