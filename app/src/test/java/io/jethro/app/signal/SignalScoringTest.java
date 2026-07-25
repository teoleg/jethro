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
        // average return over ALL resolved = (0.02+0.02−0.02+0.0005)/4 = 0.005125 → 51.25 bps.
        // (This expectation read 101.25 and had been failing: the sum is 0.0205, not 0.0405.)
        assertEquals(51.25, s.avgReturnBps(), 1e-9);
        // Sample sd (ADR-0064): deviations from 0.005125 are +0.014875, +0.014875, −0.025125, −0.004625;
        // Σd² = 0.0010951875; /(n−1)=3 → 3.650625e−4; √ = 0.0191066088… → 191.0661 bps.
        assertEquals(191.06608804, s.stdReturnBps(), 1e-8);
        // Standard error = sd/√4 = 95.533 bps — the mean sits well inside one, i.e. it is not evidence.
        assertEquals(95.53304402, s.stdErrorBps(), 1e-8);
    }

    @Test
    void emptyIsZeroedNotNaN() {
        SignalScoring.Stats s = SignalScoring.aggregate("social", List.of(), FLAT_BPS, 0);
        assertEquals(0, s.resolved());
        assertEquals(0.0, s.hitRate(), 1e-12);
        assertEquals(0.0, s.avgReturnBps(), 1e-12);
        assertEquals(0.0, s.stdReturnBps(), 1e-12);
        assertEquals(0.0, s.stdErrorBps(), 1e-12);
    }

    @Test
    void aSingleObservationHasNoDispersion() {
        // One sample cannot estimate a dispersion — it must read zero, never NaN or a divide-by-zero.
        SignalScoring.Stats s = SignalScoring.aggregate("learned", List.of(0.02), FLAT_BPS, 0);
        assertEquals(200.0, s.avgReturnBps(), 1e-9);
        assertEquals(0.0, s.stdReturnBps(), 1e-12);
        assertEquals(0.0, s.stdErrorBps(), 1e-12);
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v);
    }
}
