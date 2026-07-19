package io.jethro.trading.algo.strategy;

import io.jethro.trading.algo.strategy.Strategy.Observation;
import io.jethro.trading.algo.strategy.TrendDetector.Regime;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Price-derived regime detection (ADR-0044): efficiency ratio, hysteresis, breadth — from prices
 *  only, never the sim's regime label (invariant 8). Exact BigDecimal prices throughout. */
class TrendDetectorTest {

    private static Observation obs(String id, String price) {
        return new Observation(id, new BigDecimal(price), false);
    }

    private static void feed(TrendDetector det, String id, String... prices) {
        for (String p : prices) {
            det.update(List.of(obs(id, p)));
        }
    }

    @Test
    void warmingWindowIsUnknown() {
        TrendDetector det = new TrendDetector(4, new BigDecimal("0.5"), new BigDecimal("0.3"));
        feed(det, "AAPL", "100", "101", "102"); // only 3 of the needed 5 prices
        assertEquals(Regime.UNKNOWN, det.regimeFor("AAPL"));
    }

    @Test
    void cleanUptrendIsTrend() {
        TrendDetector det = new TrendDetector(4, new BigDecimal("0.5"), new BigDecimal("0.3"));
        feed(det, "AAPL", "100", "101", "102", "103", "104"); // ER = 4/4 = 1.0
        assertEquals(Regime.TREND, det.regimeFor("AAPL"));
    }

    @Test
    void oscillationIsChop() {
        TrendDetector det = new TrendDetector(4, new BigDecimal("0.5"), new BigDecimal("0.3"));
        feed(det, "AAPL", "100", "101", "100", "101", "100"); // net 0, steps 4 → ER 0
        assertEquals(Regime.CHOP, det.regimeFor("AAPL"));
    }

    @Test
    void hysteresisHoldsInTheNeutralBandThenFlips() {
        TrendDetector det = new TrendDetector(2, new BigDecimal("0.5"), new BigDecimal("0.3"));
        feed(det, "X", "10", "11", "12");   // ER 1.0 → TREND
        assertEquals(Regime.TREND, det.regimeFor("X"));
        feed(det, "X", "11.5");             // window [11,12,11.5]: ER 0.5/1.5 = 0.333 (neutral) → hold
        assertEquals(Regime.TREND, det.regimeFor("X"), "neutral-band ER must hold the established regime");
        feed(det, "X", "12.3");             // window [12,11.5,12.3]: ER 0.3/1.3 = 0.231 ≤ lower → CHOP
        assertEquals(Regime.CHOP, det.regimeFor("X"), "ER below the lower band flips to chop");
    }

    @Test
    void breadthAggregatesAcrossTheUniverse() {
        TrendDetector det = new TrendDetector(2, new BigDecimal("0.5"), new BigDecimal("0.3"));
        // Two names both cleanly trending → market breadth reads TREND.
        for (String p : new String[]{"10", "11", "12"}) {
            det.update(List.of(obs("A", p), obs("B", p)));
        }
        assertEquals(Regime.TREND, det.breadth());
    }
}
