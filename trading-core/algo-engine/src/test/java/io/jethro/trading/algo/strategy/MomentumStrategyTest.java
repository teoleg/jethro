package io.jethro.trading.algo.strategy;

import io.jethro.domain.Side;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vol-adaptive momentum signals. Worked example used below (lookback 4, single jump in an
 * otherwise flat window [100,100,100,100,102]): returns [0,0,0,ln1.02], mean .00495,
 * sample σ = .009895, move = ln1.02 = .019803 → z = .019803/(.009895·√4) ≈ 1.0006.
 */
class MomentumStrategyTest {

    private static MomentumStrategy.Observation obs(String price) {
        return new MomentumStrategy.Observation("AAPL", new BigDecimal(price), false);
    }

    private static List<TradeSignal> feedSeries(MomentumStrategy s, String... prices) {
        List<TradeSignal> last = List.of();
        for (String p : prices) {
            last = s.evaluate(List.of(obs(p)));
        }
        return last;
    }

    private static List<TradeSignal> feedSeriesVol(MomentumStrategy s, double relVol, String... prices) {
        List<TradeSignal> last = List.of();
        for (String p : prices) {
            last = s.evaluate(List.of(new MomentumStrategy.Observation("AAPL", new BigDecimal(p), false, relVol)));
        }
        return last;
    }

    @Test
    void volumeConfirmationGatesThinBreakouts() {
        // The worked-example jump (z ≈ 1.0) clears 0.95σ; require relativeVolume ≥ 0.8 to confirm.
        var gated = new MomentumStrategy(4, 0.95, new BigDecimal("2"), 0.8);
        assertTrue(feedSeriesVol(gated, 0.3, "100", "100", "100", "100", "102").isEmpty(),
                "a breakout on thin volume (0.3×) is discarded");
        var confirmed = new MomentumStrategy(4, 0.95, new BigDecimal("2"), 0.8);
        assertEquals(1, feedSeriesVol(confirmed, 1.5, "100", "100", "100", "100", "102").size(),
                "the same move with participation (1.5×) fires");
        // Neutral default (relativeVolume 1.0, gate 0) is unaffected — the backtest path.
        var ungated = new MomentumStrategy(4, 0.95, new BigDecimal("2"));
        assertEquals(1, feedSeries(ungated, "100", "100", "100", "100", "102").size());
    }

    @Test
    void noSignalUntilTheWindowIsFull() {
        var s = new MomentumStrategy(4, 1.0, new BigDecimal("2"));
        assertTrue(feedSeries(s, "100", "100", "100", "100").isEmpty(), "needs lookback+1 prices");
    }

    @Test
    void flatWindowNeverSignals() {
        var s = new MomentumStrategy(4, 0.5, new BigDecimal("0"));
        assertTrue(feedSeries(s, "100", "100", "100", "100", "100").isEmpty());
    }

    @Test
    void jumpFiresWhenItsZScoreClearsTheThreshold() {
        // z ≈ 1.0006 (worked example above): fires at 0.95σ, not at 1.2σ.
        var loose = new MomentumStrategy(4, 0.95, new BigDecimal("2"));
        List<TradeSignal> signals = feedSeries(loose, "100", "100", "100", "100", "102");
        assertEquals(1, signals.size());
        assertEquals(Side.BUY, signals.get(0).side());
        assertEquals(0, new BigDecimal("200").compareTo(
                signals.get(0).changeBps().setScale(0, java.math.RoundingMode.HALF_UP)));
        assertEquals(1.0006, signals.get(0).zScore(), 0.01);

        var tight = new MomentumStrategy(4, 1.2, new BigDecimal("2"));
        assertTrue(feedSeries(tight, "100", "100", "100", "100", "102").isEmpty());
    }

    @Test
    void downJumpFiresSell() {
        var s = new MomentumStrategy(4, 0.95, new BigDecimal("2"));
        List<TradeSignal> signals = feedSeries(s, "100", "100", "100", "100", "98");
        assertEquals(1, signals.size());
        assertEquals(Side.SELL, signals.get(0).side());
    }

    @Test
    void steadyTrendIsInfiniteZAndSignals() {
        // Equal returns each step → σ = 0, move > 0 → trending, fires at any threshold.
        var s = new MomentumStrategy(4, 5.0, new BigDecimal("2"));
        List<TradeSignal> signals =
                feedSeries(s, "100", "101", "102.01", "103.0301", "104.060401");
        assertEquals(1, signals.size());
        assertEquals(Side.BUY, signals.get(0).side());
        assertTrue(Double.isInfinite(signals.get(0).zScore()));
    }

    @Test
    void dustMovesBelowTheBpsFloorAreIgnoredEvenAtHighZ() {
        // Same z ≈ 1.0 shape but the move is 0.01bps — below the 2bps floor.
        var s = new MomentumStrategy(4, 0.9, new BigDecimal("2"));
        assertTrue(feedSeries(s, "100", "100", "100", "100", "100.0001").isEmpty());
    }

    @Test
    void staleMarksAreIgnored() {
        var s = new MomentumStrategy(2, 0.5, new BigDecimal("0"));
        for (int i = 0; i < 5; i++) {
            assertTrue(s.evaluate(List.of(new MomentumStrategy.Observation(
                    "AAPL", new BigDecimal(100 + i * 10), true))).isEmpty());
        }
    }
}
