package io.jethro.app.strategy;

import io.jethro.trading.riskpnl.PositionRisk;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exact-value tests for the stop-loss / take-profit decision (finance-math rule: the
 * return math is hand-computed and pinned). Sign convention: quantity &gt; 0 long, a stop
 * fires when the position is losing, take-profit when winning.
 */
class StrategyExitTest {

    private static final BigDecimal STOP = new BigDecimal("0.008");   // 0.8%
    private static final BigDecimal TP = new BigDecimal("0.016");     // 1.6%

    private static PositionRisk pos(String assetClass, String qty, String avgCost, String mark, boolean hasMark) {
        BigDecimal q = new BigDecimal(qty);
        return new PositionRisk("ALPHA", "AAPL", assetClass, "USD", q,
                new BigDecimal(avgCost), new BigDecimal(mark), hasMark, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    @Test
    void longStopsWhenPriceFallsPastThreshold() {
        // (188.40 − 190)/190 = −0.00842 ≤ −0.008 → stop
        Optional<String> r = StrategyLifecycle.priceExitReason(
                pos("EQUITY", "100", "190.00", "188.40", true), STOP, TP);
        assertTrue(r.isPresent() && r.get().startsWith("stop-loss"), () -> "was " + r);
    }

    @Test
    void longHoldsInsideTheBand() {
        // (189.00 − 190)/190 = −0.00526, above the −0.008 stop and below +0.016 → hold
        assertEquals(Optional.empty(),
                StrategyLifecycle.priceExitReason(pos("EQUITY", "100", "190.00", "189.00", true), STOP, TP));
    }

    @Test
    void longTakesProfitWhenPriceRisesPastThreshold() {
        // (193.10 − 190)/190 = +0.01632 ≥ +0.016 → take-profit
        Optional<String> r = StrategyLifecycle.priceExitReason(
                pos("EQUITY", "100", "190.00", "193.10", true), STOP, TP);
        assertTrue(r.isPresent() && r.get().startsWith("take-profit"), () -> "was " + r);
    }

    @Test
    void shortStopsWhenPriceRises() {
        // short 1 @ 190, mark 191.60: (191.60 − 190)/190 × sign(−1) = −0.00842 → stop
        Optional<String> r = StrategyLifecycle.priceExitReason(
                pos("FUTURE", "-1", "190.00", "191.60", true), STOP, TP);
        assertTrue(r.isPresent() && r.get().startsWith("stop-loss"), () -> "was " + r);
    }

    @Test
    void shortTakesProfitWhenPriceFalls() {
        // short 1 @ 190, mark 186.90: (186.90 − 190)/190 × sign(−1) = +0.01632 → take-profit
        Optional<String> r = StrategyLifecycle.priceExitReason(
                pos("FUTURE", "-1", "190.00", "186.90", true), STOP, TP);
        assertTrue(r.isPresent() && r.get().startsWith("take-profit"), () -> "was " + r);
    }

    @Test
    void swapsAreNotManagedByPriceExits() {
        // Par-rate quoted: a 0.8% move on a 4% rate is ~3bp — pct exits would be incoherent.
        assertEquals(Optional.empty(),
                StrategyLifecycle.priceExitReason(pos("SWAP", "1", "4.00", "3.90", true), STOP, TP));
    }

    @Test
    void marklessOrZeroCostPositionsHold() {
        assertEquals(Optional.empty(),
                StrategyLifecycle.priceExitReason(pos("EQUITY", "100", "190.00", "0", false), STOP, TP));
        assertEquals(Optional.empty(),
                StrategyLifecycle.priceExitReason(pos("EQUITY", "100", "0", "190.00", true), STOP, TP));
    }

    @Test
    void disabledThresholdsNeverExit() {
        assertEquals(Optional.empty(),
                StrategyLifecycle.priceExitReason(pos("EQUITY", "100", "190.00", "150.00", true), null, null));
    }
}
