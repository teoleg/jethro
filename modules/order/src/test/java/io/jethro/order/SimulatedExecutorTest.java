package io.jethro.order;

import io.jethro.domain.BookId;
import io.jethro.domain.Fill;
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

/** Execution semantics: MARKET fills at the mark, LIMIT only when the mark is crossed. */
class SimulatedExecutorTest {

    private final SimulatedExecutor executor = new SimulatedExecutor(ExecutionCostSource.FREE);

    private static Order order(OrderType type, Side side, String qty, String limit) {
        return new Order("ord-1", "idem-1", new BookId("ALPHA"), new InstrumentId("AAPL"),
                side, type, new BigDecimal(qty),
                limit == null ? Optional.empty() : Optional.of(new BigDecimal(limit)),
                TimeInForce.GTC, OrderStatus.ROUTED, Instant.EPOCH);
    }

    @Test
    void marketOrderFillsFullQuantityAtTheMark() {
        Optional<Fill> fill = executor.tryExecute(order(OrderType.MARKET, Side.BUY, "100", null),
                new BigDecimal("150.25"));

        assertTrue(fill.isPresent());
        assertEquals(0, new BigDecimal("100").compareTo(fill.get().quantity()));
        assertEquals(0, new BigDecimal("150.25").compareTo(fill.get().price()));
        assertEquals(Side.BUY, fill.get().side());
    }

    @Test
    void marketOrderWithoutAMarkCannotFill() {
        assertTrue(executor.tryExecute(order(OrderType.MARKET, Side.BUY, "100", null), null).isEmpty());
    }

    @Test
    void buyLimitFillsAtTheLimitWhenMarketIsAtOrBelow() {
        // Mark 149 <= limit 150 → marketable; the buyer pays the limit, not the (better) mark.
        Optional<Fill> fill = executor.tryExecute(order(OrderType.LIMIT, Side.BUY, "10", "150.00"),
                new BigDecimal("149.00"));

        assertTrue(fill.isPresent());
        assertEquals(0, new BigDecimal("150.00").compareTo(fill.get().price()));
    }

    @Test
    void buyLimitDoesNotFillWhenMarketIsAboveTheLimit() {
        // Mark 151 > limit 150 → not marketable, order stays working.
        assertTrue(executor.tryExecute(order(OrderType.LIMIT, Side.BUY, "10", "150.00"),
                new BigDecimal("151.00")).isEmpty());
    }

    @Test
    void sellLimitFillsWhenMarketIsAtOrAboveTheLimit() {
        Optional<Fill> fill = executor.tryExecute(order(OrderType.LIMIT, Side.SELL, "10", "150.00"),
                new BigDecimal("150.00"));

        assertTrue(fill.isPresent());
        assertEquals(0, new BigDecimal("150.00").compareTo(fill.get().price()));
    }

    @Test
    void sellLimitDoesNotFillWhenMarketIsBelowTheLimit() {
        assertTrue(executor.tryExecute(order(OrderType.LIMIT, Side.SELL, "10", "150.00"),
                new BigDecimal("149.99")).isEmpty());
    }
}
