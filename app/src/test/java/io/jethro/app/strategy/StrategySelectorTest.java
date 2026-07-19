package io.jethro.app.strategy;

import io.jethro.app.backtest.BacktestResult;
import io.jethro.trading.algo.strategy.SelectingStrategy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void neitherPositiveIsNoTrade() {
        var mom = Map.of("ZN", ir("ZN", 5, "-40"));
        var mr = Map.of("ZN", ir("ZN", 5, "-10"));
        assertEquals(SelectingStrategy.NO_TRADE, StrategySelector.choose(mom, mr).get("ZN").algo());
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
}
