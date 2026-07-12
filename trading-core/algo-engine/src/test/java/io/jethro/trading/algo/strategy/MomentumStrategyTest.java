package io.jethro.trading.algo.strategy;

import io.jethro.domain.Side;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deterministic momentum signals from a price series — exact, seed-free. */
class MomentumStrategyTest {

    // lookback 2, threshold 50bps (0.50%)
    private final MomentumStrategy strategy = new MomentumStrategy(2, new BigDecimal("50"));

    private static MomentumStrategy.Observation obs(String price) {
        return new MomentumStrategy.Observation("AAPL", new BigDecimal(price), false);
    }

    private List<TradeSignal> feed(String price) {
        return strategy.evaluate(List.of(obs(price)));
    }

    @Test
    void noSignalUntilTheWindowIsFull() {
        assertTrue(feed("100").isEmpty());   // 1 obs
        assertTrue(feed("100").isEmpty());   // 2 obs — window needs lookback+1 = 3
    }

    @Test
    void buyWhenPriceRisesPastTheThreshold() {
        feed("100");
        feed("100");
        // window [100,100,100.6] vs ref 100 → +60bps >= 50 → BUY
        List<TradeSignal> signals = feed("100.60");
        assertEquals(1, signals.size());
        assertEquals(Side.BUY, signals.get(0).side());
        assertEquals(0, new BigDecimal("60").compareTo(signals.get(0).changeBps()));
        assertEquals(0, new BigDecimal("100").compareTo(signals.get(0).referencePrice()));
    }

    @Test
    void sellWhenPriceFallsPastTheThreshold() {
        feed("100");
        feed("100");
        // -70bps <= -50 → SELL
        List<TradeSignal> signals = feed("99.30");
        assertEquals(1, signals.size());
        assertEquals(Side.SELL, signals.get(0).side());
    }

    @Test
    void noSignalForMovesInsideTheThreshold() {
        feed("100");
        feed("100");
        assertTrue(feed("100.40").isEmpty(), "+40bps < 50bps threshold → no signal");
    }

    @Test
    void referenceRollsForwardSoASettledPriceStopsSignalling() {
        feed("100");
        feed("101");
        // [100,101,101], ref 100 → +100bps → BUY
        assertEquals(Side.BUY, feed("101").get(0).side());
        // window rolls to [101,101,101], ref 101 → 0bps → no further signal
        assertTrue(feed("101").isEmpty(), "reference rolled forward to 101");
    }

    @Test
    void staleMarksAreIgnored() {
        var s = new MomentumStrategy(1, new BigDecimal("10"));
        s.evaluate(List.of(new MomentumStrategy.Observation("AAPL", new BigDecimal("100"), true)));
        s.evaluate(List.of(new MomentumStrategy.Observation("AAPL", new BigDecimal("200"), true)));
        assertTrue(s.evaluate(List.of(new MomentumStrategy.Observation("AAPL", new BigDecimal("300"), true))).isEmpty());
    }
}
