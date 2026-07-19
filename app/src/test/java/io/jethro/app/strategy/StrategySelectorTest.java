package io.jethro.app.strategy;

import io.jethro.app.backtest.BacktestResult;
import io.jethro.trading.algo.strategy.SelectingStrategy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The pure per-instrument choice (ADR-0043): higher positive-median algo wins; neither positive
 *  → NO_TRADE; a positive median with zero trades does not qualify. */
class StrategySelectorTest {

    private static BacktestResult.InstrumentResult ir(String id, int trades, String medianPnl) {
        // aggregateByMedian reports the median total PnL in realizedPnl (unrealized 0).
        return new BacktestResult.InstrumentResult(id, trades, new BigDecimal(medianPnl),
                BigDecimal.ZERO, BigDecimal.ZERO);
    }

    @Test
    void picksTheHigherPositiveMedian() {
        var mom = Map.of("AAPL", ir("AAPL", 8, "-120"));   // momentum loses
        var mr = Map.of("AAPL", ir("AAPL", 6, "85"));      // mean-reversion wins
        var choice = StrategySelector.choose(mom, mr).get("AAPL");
        assertEquals("mean-reversion", choice.algo());
    }

    @Test
    void bothPositiveTakesTheLarger() {
        var mom = Map.of("MSFT", ir("MSFT", 10, "50"));
        var mr = Map.of("MSFT", ir("MSFT", 10, "30"));
        assertEquals("momentum", StrategySelector.choose(mom, mr).get("MSFT").algo());
    }

    @Test
    void neitherPositiveButTradedIsNoTrade() {
        // Both algos DID trade and both lost → a genuine measured no-edge → NO_TRADE.
        var mom = Map.of("ZN", ir("ZN", 5, "-40"));
        var mr = Map.of("ZN", ir("ZN", 5, "-10"));
        assertEquals(SelectingStrategy.NO_TRADE, StrategySelector.choose(mom, mr).get("ZN").algo());
    }

    @Test
    void noMeasurementIsAbsentSoTheDefaultAlgoRuns() {
        // Neither algo traded in the backtest → NOT measured → leave it out of the map so the
        // live SelectingStrategy falls back to the default and keeps trading (not suppressed).
        var mom = Map.of("AAPL", ir("AAPL", 0, "0"));
        var mr = Map.of("AAPL", ir("AAPL", 0, "0"));
        var choices = StrategySelector.choose(mom, mr);
        assertFalse(choices.containsKey("AAPL"), "a no-measurement name must not be in the selection");
    }

    @Test
    void positiveMedianButNoTradesDoesNotQualify() {
        // A positive median with zero trades is not a real edge — it never traded.
        var mom = Map.of("NVDA", ir("NVDA", 0, "100"));
        var mr = Map.of("NVDA", ir("NVDA", 4, "20"));
        assertEquals("mean-reversion", StrategySelector.choose(mom, mr).get("NVDA").algo());
    }

    @Test
    void instrumentInOnlyOneMapIsHandled() {
        var mom = Map.of("AMZN", ir("AMZN", 7, "45"));
        var mr = Map.<String, BacktestResult.InstrumentResult>of();
        assertEquals("momentum", StrategySelector.choose(mom, mr).get("AMZN").algo());
    }

    /** The heap guard (the fix for the ~1h whole-JVM freeze): the hourly backtest spike is skipped
     *  when free heap headroom falls below the guard fraction, so it can't stall/OOM the server. */
    @Test
    void heapGuardTripsWhenHeadroomIsThin() {
        long max = 512L * 1024 * 1024;
        // 80% used → 20% headroom, below the 35% guard → skip.
        org.junit.jupiter.api.Assertions.assertTrue(
                StrategySelector.lowHeap(max, (long) (max * 0.80), 0.35));
        // 50% used → 50% headroom, above the guard → run.
        assertFalse(StrategySelector.lowHeap(max, (long) (max * 0.50), 0.35));
        // exactly at the boundary (35% free) is not "below" → run.
        assertFalse(StrategySelector.lowHeap(max, (long) (max * 0.65), 0.35));
    }
}
