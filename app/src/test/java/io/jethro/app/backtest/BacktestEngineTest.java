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
                new BigDecimal("25000"), new BigDecimal("50000"), new BigDecimal("75000"), false,
                BigDecimal.ONE, costBps,
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
                new BigDecimal("25000"), new BigDecimal("50000"), new BigDecimal("75000"), true,
                BigDecimal.ONE, costBps,
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
    void longOnlyNeverGoesShort() {
        BacktestResult r = engine.run(config(42));
        for (BacktestResult.InstrumentResult ir : r.byInstrument()) {
            assertTrue(ir.endPosition().signum() >= 0, "long-only: " + ir.instrumentId() + " went short");
        }
    }

    /**
     * ADR-0085: a name whose unit is a CONTRACT sizes FRACTIONALLY, exactly as the live order path does
     * (ADR-0078 {@code TargetPlanner.tradableQuantity}) and as the hedge advisor has always submitted on
     * ES. Worked: one ES contract is worth 5450 × 50 = 272,500, so a 25,000 target notional is
     * 25,000 / 272,500 = 0.091743… contracts, DOWN at scale 6 = 0.091743 — worth 0.091743 × 5450 × 50 =
     * 24,999.97, the size that was asked for. Rounding that to a whole contract gave 0, and because one
     * contract exceeds the 50,000 order cap the name was then declared unsizeable and never traded —
     * which is why no future ever earned an OOS verdict, and so why the ADR-0049 veto barred every one
     * of them from the fusion desk.
     */
    @Test
    void aContractSizesFractionallyAndTradesRatherThanBeingUnsizeable() {
        BacktestResult r = engine.run(tradingConfigWithEs(BigDecimal.ZERO));
        BacktestResult.InstrumentResult es = r.byInstrument().stream()
                .filter(ir -> ir.instrumentId().equals("ES")).findFirst().orElseThrow();
        assertTrue(es.trades() > 0, "ES must now be sizeable in its own contract terms");
        // Every quantity it traded is a multiple of the 1e-6 contract scale, never a whole-contract jump.
        assertTrue(es.endPosition().abs().compareTo(new BigDecimal("1")) < 0,
                "a 25k target notional in ES is a fraction of one contract, not a whole one");
    }

    /**
     * ADR-0085: each name is charged its OWN per-fill cost, not one blend. Worked on ES: its per-fill
     * cost is half its own full spread plus its class fee — 0.46 / 2 + 0.2 = 0.43 bps — where the old
     * code charged it the EQUITY blend of 5 / 2 + 1 = 3.5 bps, 8.1x too much. Same tape, same fills:
     * only the charge differs, and it differs LINEARLY in the rate, so doubling ES's rate exactly
     * doubles the cost attributed to ES while AAPL's (held at zero here) stays zero.
     */
    @Test
    void eachNameIsChargedItsOwnCost() {
        BigDecimal free = engine.run(esAt(BigDecimal.ZERO, BigDecimal.ZERO)).totalCosts();
        assertEquals(0, free.signum(), "zero rates -> no costs");
        BigDecimal atRate = engine.run(esAt(new BigDecimal("0.43"), BigDecimal.ZERO)).totalCosts();
        BigDecimal atDouble = engine.run(esAt(new BigDecimal("0.86"), BigDecimal.ZERO)).totalCosts();
        assertTrue(atRate.signum() > 0, "ES must trade for the charge to be observable");
        // AAPL is free in both runs, so every cost here is ES's, and cost = rate x notional is linear.
        assertEquals(0, atDouble.compareTo(atRate.multiply(BigDecimal.TWO)),
                "ES's cost must scale exactly with ES's own rate");
    }

    /** Aggressive params over a universe that includes ES, so both sizing rules are exercised. */
    private static BacktestConfig tradingConfigWithEs(BigDecimal costBps) {
        return esAt(costBps, costBps);
    }

    /** As above with ES and AAPL priced separately — the per-name cost path. */
    private static BacktestConfig esAt(BigDecimal esCostBps, BigDecimal aaplCostBps) {
        return new BacktestConfig(42, 30_000, true, 50,
                12, 0.5, BigDecimal.ONE,
                new BigDecimal("25000"), new BigDecimal("50000"), new BigDecimal("75000"), true,
                BigDecimal.ONE, aaplCostBps,
                List.of(new BacktestConfig.Instrument("AAPL", new BigDecimal("190"), 0.28, BigDecimal.ONE,
                                aaplCostBps),
                        new BacktestConfig.Instrument("ES", new BigDecimal("5450"), 0.15, new BigDecimal("50"),
                                esCostBps)));
    }
}
