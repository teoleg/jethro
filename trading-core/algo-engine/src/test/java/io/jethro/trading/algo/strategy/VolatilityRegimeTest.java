package io.jethro.trading.algo.strategy;

import io.jethro.trading.algo.strategy.Strategy.Observation;
import io.jethro.trading.algo.strategy.VolatilityRegime.Regime;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Price-derived volatility-regime sensing (ADR-0051): CALM until a full window, CALM under small
 *  moves, ELEVATED when realized volatility spikes above its own baseline — from prices only, never
 *  a sim label. Exact BigDecimal prices. */
class VolatilityRegimeTest {

    private static void feed(VolatilityRegime det, String id, String... prices) {
        for (String p : prices) {
            det.update(List.of(new Observation(id, new BigDecimal(p), false)));
        }
    }

    @Test
    void warmingWindowIsUnknown() {
        var det = new VolatilityRegime(4, new BigDecimal("1.5"), new BigDecimal("1.1"), new BigDecimal("0.9"));
        feed(det, "X", "100", "100.1", "100.2"); // < window+1 prices
        assertEquals(Regime.UNKNOWN, det.regime());
    }

    @Test
    void smallMovesAreCalm() {
        var det = new VolatilityRegime(4, new BigDecimal("1.5"), new BigDecimal("1.1"), new BigDecimal("0.9"));
        feed(det, "X", "100", "100.1", "100.2", "100.3", "100.4", "100.5", "100.6", "100.7");
        assertEquals(Regime.CALM, det.regime());
    }

    @Test
    void aVolatilitySpikeAboveBaselineIsElevated() {
        var det = new VolatilityRegime(4, new BigDecimal("1.5"), new BigDecimal("1.1"), new BigDecimal("0.9"));
        // Establish a calm baseline (0.1 steps), then a burst of ~5.0 steps → realized vol ≫ baseline.
        feed(det, "X", "100", "100.1", "100.2", "100.3", "100.4", "100.5", "100.6", "100.7");
        assertEquals(Regime.CALM, det.regime());
        feed(det, "X", "105.7", "110.7", "115.7", "120.7", "125.7");
        assertEquals(Regime.ELEVATED, det.regime(), "a realized-vol spike above baseline is risk-off");
    }

    private static void feedOne(VolatilityRegime det, String price) {
        det.update(List.of(new Observation("X", new BigDecimal(price), false)));
    }

    @Test
    void sustainedTurbulenceStaysElevatedThenReturnsToCalmWhenItSubsides() {
        // Fast baseline (λ=0.9, halflife ~6.6 updates): WITHOUT the asymmetric freeze the baseline would
        // climb to the turbulent level within ~a dozen updates and the regime would wash back to CALM
        // while the market is still turbulent (the bug behind "sim set to VOLATILE but badge shows CALM").
        var det = new VolatilityRegime(4, new BigDecimal("1.5"), new BigDecimal("1.1"), new BigDecimal("0.9"));

        // Calm baseline: a tiny 0.1 oscillation (|Δp| ≈ 0.1).
        for (int i = 0; i < 10; i++) {
            feedOne(det, i % 2 == 0 ? "100.0" : "100.1");
        }
        assertEquals(Regime.CALM, det.regime());

        // Sustained turbulence: a 5.0 oscillation (|Δp| ≈ 5) for FAR longer than the baseline halflife.
        for (int i = 0; i < 40; i++) {
            feedOne(det, i % 2 == 0 ? "100.0" : "105.0");
        }
        assertEquals(Regime.ELEVATED, det.regime(), "a SUSTAINED turbulent regime stays flagged, not just its onset");

        // Vol subsides: back to the tiny oscillation → recalibrates to CALM within a few windows.
        for (int i = 0; i < 12; i++) {
            feedOne(det, i % 2 == 0 ? "100.0" : "100.1");
        }
        assertEquals(Regime.CALM, det.regime(), "once vol actually subsides it returns to CALM");
    }
}
