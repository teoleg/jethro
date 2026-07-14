package io.jethro.order;

import io.jethro.domain.BookId;
import io.jethro.domain.InstrumentId;
import io.jethro.domain.Order;
import io.jethro.domain.OrderStatus;
import io.jethro.domain.OrderType;
import io.jethro.domain.Side;
import io.jethro.domain.TimeInForce;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Execution cost model (ADR-0025), exact worked examples (finance-math rule):
 * mid is the mark; a marketable order crosses the half-spread; MARKET pays the fee.
 */
class ExecutionCostTest {

    private static final ExecutionCostSource EQUITY_COSTS = id ->
            new ExecutionCostSource.Cost(new BigDecimal("5"), new BigDecimal("1"), false);
    private static final ExecutionCostSource SWAP_COSTS = id ->
            new ExecutionCostSource.Cost(new BigDecimal("0.4"), BigDecimal.ZERO, true);

    private static Order order(Side side, OrderType type, String limit) {
        return new Order("ord-1", "key-1", new BookId("ALPHA"), new InstrumentId("AAPL"),
                side, type, new BigDecimal("131"),
                Optional.ofNullable(limit).map(BigDecimal::new), TimeInForce.GTC, OrderStatus.ROUTED, Instant.EPOCH);
    }

    @Test
    void buyMarketCrossesHalfSpreadAndPaysFee() {
        // mid 190.00, spread 5bp, fee 1bp:
        // touch = 190 × 1.00025 = 190.0475; fill = 190.0475 × 1.0001 = 190.06650475 → 190.066505
        var fill = new SimulatedExecutor(EQUITY_COSTS)
                .tryExecute(order(Side.BUY, OrderType.MARKET, null), new BigDecimal("190.00")).orElseThrow();
        assertEquals(new BigDecimal("190.066505"), fill.price());
    }

    @Test
    void sellMarketGetsHitOnTheBidMinusFee() {
        // touch = 190 × 0.99975 = 189.9525; fill = 189.9525 × 0.9999 = 189.93350475 → 189.933505
        var fill = new SimulatedExecutor(EQUITY_COSTS)
                .tryExecute(order(Side.SELL, OrderType.MARKET, null), new BigDecimal("190.00")).orElseThrow();
        assertEquals(new BigDecimal("189.933505"), fill.price());
    }

    @Test
    void roundTripCostIsSpreadPlusTwoFees() {
        // Round trip at an unmoved mid loses spread + 2·fee = (5 + 2)bp of notional:
        // 131 × (190.066505 − 189.933505) = 131 × 0.133 = $17.42 — churn is no longer free.
        var exec = new SimulatedExecutor(EQUITY_COSTS);
        var buy = exec.tryExecute(order(Side.BUY, OrderType.MARKET, null), new BigDecimal("190.00")).orElseThrow();
        var sell = exec.tryExecute(order(Side.SELL, OrderType.MARKET, null), new BigDecimal("190.00")).orElseThrow();
        BigDecimal roundTrip = buy.price().subtract(sell.price()).multiply(new BigDecimal("131"));
        assertEquals(new BigDecimal("17.423000"), roundTrip.setScale(6));
    }

    @Test
    void swapSpreadIsAdditiveInRateBp() {
        // Rate quote 4.00%, 0.4bp spread → pay-fixed (BUY) fills at 4.00 + 0.002 = 4.002.
        var fill = new SimulatedExecutor(SWAP_COSTS)
                .tryExecute(order(Side.BUY, OrderType.MARKET, null), new BigDecimal("4.00")).orElseThrow();
        assertEquals(new BigDecimal("4.002000"), fill.price());
    }

    @Test
    void limitMarketabilityIsAgainstTheTouchNotTheMid() {
        var exec = new SimulatedExecutor(EQUITY_COSTS);
        // BUY limit AT the mid: the ask (190.0475) is above it → NOT marketable (with free
        // execution this filled — that was the dishonesty).
        assertTrue(exec.tryExecute(order(Side.BUY, OrderType.LIMIT, "190.00"), new BigDecimal("190.00")).isEmpty());
        // BUY limit above the ask: fills AT THE LIMIT, never beyond it (no fee embedded —
        // a limit fill beyond its limit would violate limit semantics; ADR-0025 v1).
        var fill = exec.tryExecute(order(Side.BUY, OrderType.LIMIT, "190.05"), new BigDecimal("190.00")).orElseThrow();
        assertEquals(new BigDecimal("190.050000"), fill.price());
    }

    @Test
    void freeSourceFillsAtMidExactly() {
        var fill = new SimulatedExecutor(ExecutionCostSource.FREE)
                .tryExecute(order(Side.BUY, OrderType.MARKET, null), new BigDecimal("190.00")).orElseThrow();
        assertEquals(0, new BigDecimal("190.00").compareTo(fill.price()));
    }
}
