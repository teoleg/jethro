package io.jethro.app.backtest;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The backtest must be reproducible (same seed → identical result) and its PnL exact. */
class BacktestEngineTest {

    private final BacktestEngine engine = new BacktestEngine();

    private static BacktestConfig config(long seed) {
        return config(seed, BigDecimal.ZERO);
    }

    private static BacktestConfig config(long seed, BigDecimal costBps) {
        return new BacktestConfig(seed, 5_000, true, 50,
                24, 2.5, new BigDecimal("2"),
                new BigDecimal("25000"), new BigDecimal("50000"), new BigDecimal("75000"), false, costBps,
                List.of(new BacktestConfig.Instrument("AAPL", new BigDecimal("190"), 0.28, BigDecimal.ONE),
                        new BacktestConfig.Instrument("ES", new BigDecimal("5450"), 0.15, new BigDecimal("50"))));
    }

    @Test
    void sameSeedProducesIdenticalResult() {
        assertEquals(engine.run(config(42)), engine.run(config(42)));
    }

    @Test
    void differentSeedsDiverge() {
        // Not a strict guarantee, but on this universe two seeds must produce different tapes.
        assertTrue(!engine.run(config(1)).equals(engine.run(config(2))));
    }

    @Test
    void totalPnlIsRealizedPlusUnrealizedExactly() {
        BacktestResult r = engine.run(config(42));
        assertEquals(0, r.realizedPnl().add(r.unrealizedPnl()).compareTo(r.totalPnl()));
        assertEquals(r.ticks() / 50, r.evaluations());
        assertTrue(r.winRate() >= 0.0 && r.winRate() <= 1.0);
        assertTrue(r.maxDrawdown().signum() >= 0);
        assertEquals(0, r.totalCosts().signum(), "zero costBps → no costs");
    }

    // Aggressive params (low threshold, long run) so the strategy actually trades — needed
    // to observe transaction costs at all.
    private static BacktestConfig tradingConfig(BigDecimal costBps) {
        return new BacktestConfig(42, 30_000, true, 50,
                12, 0.5, BigDecimal.ONE,
                new BigDecimal("25000"), new BigDecimal("50000"), new BigDecimal("75000"), true, costBps,
                List.of(new BacktestConfig.Instrument("AAPL", new BigDecimal("190"), 0.28, BigDecimal.ONE)));
    }

    @Test
    void transactionCostsReducePnlAndAreReported() {
        BacktestResult free = engine.run(tradingConfig(BigDecimal.ZERO));
        BacktestResult withCost = engine.run(tradingConfig(new BigDecimal("2"))); // 2 bps per fill
        assertTrue(withCost.trades() > 0, "config must trade for the test to be meaningful");
        // Same tape/trades, so costs = 2bps × traded notional > 0 and drag total PnL down.
        assertTrue(withCost.totalCosts().signum() > 0);
        assertTrue(withCost.totalPnl().compareTo(free.totalPnl()) < 0);
        // realized is reported NET of costs, so it moves down by exactly the cost delta.
        assertEquals(0, free.realizedPnl().subtract(withCost.totalCosts())
                .compareTo(withCost.realizedPnl()));
    }

    @Test
    void esIsUnsizeableAndLongOnlyNeverGoesShort() {
        BacktestResult r = engine.run(config(42));
        for (BacktestResult.InstrumentResult ir : r.byInstrument()) {
            assertTrue(ir.endPosition().signum() >= 0, "long-only: " + ir.instrumentId() + " went short");
            if (ir.instrumentId().equals("ES")) {
                // One ES contract (5450 × 50 = 272,500) exceeds the 50,000 order cap → never traded.
                assertEquals(0, ir.trades(), "ES should be unsizeable under the order cap");
            }
        }
    }
}
