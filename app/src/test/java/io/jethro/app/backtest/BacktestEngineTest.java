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
        return new BacktestConfig(seed, 5_000, true, 50,
                24, 2.5, new BigDecimal("2"),
                new BigDecimal("25000"), new BigDecimal("50000"), new BigDecimal("75000"), false,
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
