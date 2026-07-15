package io.jethro.trading.algo.strategy;

import io.jethro.domain.Side;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mean reversion = the momentum detector read backwards: identical windows and z-scores,
 * opposite side. A path that makes momentum BUY must make mean reversion SELL (fade the
 * rip), with the same z and change-bps on the signal — one detector, two readings.
 */
class MeanReversionStrategyTest {

    private static List<Strategy.Observation> obs(String price) {
        return List.of(new Strategy.Observation("AAPL", new BigDecimal(price), false));
    }

    /** Feeds a perfectly steady up-trend: σ = 0 with a positive move ⇒ z = +∞ — the
     *  detector's unambiguous "trending" verdict, deterministic by construction. */
    private static List<TradeSignal> lastSignals(Strategy strategy) {
        List<TradeSignal> last = new ArrayList<>();
        for (int i = 0; i <= 10; i++) {
            last = strategy.evaluate(obs(new BigDecimal("100.00")
                    .add(new BigDecimal("0.05").multiply(BigDecimal.valueOf(i))).toPlainString()));
        }
        return last;
    }

    @Test
    void fadesTheMoveTheMomentumStrategyWouldChase() {
        var momentum = new MomentumStrategy(9, 2.0, new BigDecimal("2"));
        var meanReversion = new MeanReversionStrategy(9, 2.0, new BigDecimal("2"));

        List<TradeSignal> chase = lastSignals(momentum);
        List<TradeSignal> fade = lastSignals(meanReversion);

        assertEquals(1, chase.size(), "the spike must trip the detector");
        assertEquals(Side.BUY, chase.get(0).side(), "momentum chases the rip");
        assertEquals(1, fade.size(), "same detector, same trigger");
        assertEquals(Side.SELL, fade.get(0).side(), "mean reversion fades it");
        assertEquals(chase.get(0).zScore(), fade.get(0).zScore(), 0.0, "identical z — one detector");
        assertEquals(0, chase.get(0).changeBps().compareTo(fade.get(0).changeBps()));
        assertTrue(fade.get(0).rationale().contains("mean-reversion — fading"),
                "the rationale says what it is: " + fade.get(0).rationale());
    }

    @Test
    void quietTapeSignalsNothingForEitherReading() {
        var meanReversion = new MeanReversionStrategy(9, 2.0, new BigDecimal("2"));
        List<TradeSignal> last = List.of();
        for (int i = 0; i < 15; i++) {
            last = meanReversion.evaluate(obs("100.0" + (i % 2))); // tiny oscillation
        }
        assertTrue(last.isEmpty(), "no extreme, nothing to fade");
    }
}
