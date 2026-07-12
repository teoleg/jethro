package io.jethro.order;

import io.jethro.domain.Fill;
import io.jethro.domain.InstrumentId;
import io.jethro.domain.Order;
import io.jethro.domain.OrderStatus;
import io.jethro.domain.OrderType;
import io.jethro.domain.Side;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Order lifecycle orchestration, DB-free via an in-memory {@link OrderStore}. Covers the
 * state machine (NEW → ROUTED → FILLED/REJECTED), idempotency (invariant 6), and that
 * every fill both persists and publishes (fills are the source of truth, invariant 3).
 */
class OrderServiceTest {

    /** In-memory OrderStore: honours the idempotency-key uniqueness the JDBC store enforces. */
    private static final class InMemoryStore implements OrderStore {
        final Map<String, Order> byKey = new HashMap<>();
        final Map<String, Order> byId = new HashMap<>();
        final List<Fill> fills = new ArrayList<>();

        @Override
        public boolean insertIfAbsent(Order order, Instant now) {
            if (byKey.containsKey(order.idempotencyKey())) {
                return false;
            }
            byKey.put(order.idempotencyKey(), order);
            byId.put(order.orderId(), order);
            return true;
        }

        @Override
        public void updateStatus(String orderId, OrderStatus status, String reason, Instant now) {
            Order updated = byId.get(orderId).withStatus(status);
            byId.put(orderId, updated);
            byKey.put(updated.idempotencyKey(), updated);
        }

        @Override
        public void insertFill(Fill fill) {
            fills.add(fill);
        }

        @Override
        public Optional<Order> findByIdempotencyKey(String idempotencyKey) {
            return Optional.ofNullable(byKey.get(idempotencyKey));
        }
    }

    /** Recording publisher: counts lifecycle events and fills emitted downstream. */
    private static final class RecordingPublisher implements OrderEventPublisher {
        final List<OrderStatus> orderEvents = new ArrayList<>();
        final List<Fill> fillEvents = new ArrayList<>();

        @Override
        public void publishOrderEvent(Order order, String reason) {
            orderEvents.add(order.status());
        }

        @Override
        public void publishFill(Fill fill) {
            fillEvents.add(fill);
        }
    }

    private final InMemoryStore store = new InMemoryStore();
    private final LastPriceCache prices = new LastPriceCache();
    private final RecordingPublisher publisher = new RecordingPublisher();
    private final OrderService service =
            new OrderService(store, new SimulatedExecutor(), prices, publisher);

    private NewOrder market(String key, Side side, String qty) {
        return new NewOrder(key, "ALPHA", "AAPL", side, OrderType.MARKET, new BigDecimal(qty), null);
    }

    @Test
    void marketOrderWithAMarkFillsAndPublishesTheFill() {
        prices.update("AAPL", new BigDecimal("150.00"));

        Order result = service.submit(market("idem-1", Side.BUY, "100"));

        assertEquals(OrderStatus.FILLED, result.status());
        assertEquals(1, store.fills.size(), "fill must persist — source of truth for positions");
        assertEquals(0, new BigDecimal("150.00").compareTo(store.fills.get(0).price()));
        assertEquals(1, publisher.fillEvents.size(), "fill must be published downstream");
        // Lifecycle events: NEW, ROUTED, FILLED.
        assertEquals(List.of(OrderStatus.NEW, OrderStatus.ROUTED, OrderStatus.FILLED),
                publisher.orderEvents);
    }

    @Test
    void marketOrderWithoutAMarkIsRejected() {
        Order result = service.submit(market("idem-1", Side.BUY, "100"));

        assertEquals(OrderStatus.REJECTED, result.status());
        assertTrue(store.fills.isEmpty(), "no fill on a rejected order");
        assertTrue(publisher.fillEvents.isEmpty());
    }

    @Test
    void resubmittingTheSameKeyReturnsTheExistingOrderWithoutASecondFill() {
        prices.update("AAPL", new BigDecimal("150.00"));

        Order first = service.submit(market("idem-dup", Side.BUY, "100"));
        Order second = service.submit(market("idem-dup", Side.BUY, "100"));

        assertEquals(first.orderId(), second.orderId(), "same command → same order (invariant 6)");
        assertEquals(1, store.fills.size(), "duplicate submit must not fill twice");
        assertEquals(1, store.byId.size(), "only one order persisted");
    }

    @Test
    void limitOrderThatIsNotMarketableStaysWorking() {
        prices.update("AAPL", new BigDecimal("151.00")); // above the buy limit
        NewOrder limit =
                new NewOrder("idem-lim", "ALPHA", "AAPL", Side.BUY, OrderType.LIMIT,
                        new BigDecimal("10"), new BigDecimal("150.00"));

        Order result = service.submit(limit);

        assertEquals(OrderStatus.ROUTED, result.status(), "unmarketable limit stays working");
        assertTrue(store.fills.isEmpty());
        assertEquals(List.of(OrderStatus.NEW, OrderStatus.ROUTED), publisher.orderEvents);
    }

    @Test
    void routedOrderCarriesThroughTheOriginalInstrument() {
        prices.update("AAPL", new BigDecimal("150.00"));
        Order result = service.submit(market("idem-1", Side.SELL, "5"));

        assertSame(Side.SELL, result.side());
        assertEquals(new InstrumentId("AAPL"), result.instrumentId());
    }
}
