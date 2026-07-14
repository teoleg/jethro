package io.jethro.order;

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
 * state machine (NEW → ROUTED → FILLED/REJECTED/CANCELLED), idempotency (invariant 6),
 * that every fill both persists and publishes (invariant 3), and the ADR-0025 lifecycle:
 * IOC cancel-on-arrival, GTC working orders filling on later marks, user cancel, and the
 * CAS guard that makes a working order fill exactly once.
 */
class OrderServiceTest {

    /** In-memory OrderStore honouring the JDBC store's semantics, incl. the CAS transition. */
    private static final class InMemoryStore implements OrderStore {
        final Map<String, Order> byKey = new HashMap<>();
        final Map<String, Order> byId = new HashMap<>();
        final List<Fill> fills = new ArrayList<>();
        final Map<String, BigDecimal> arrivals = new HashMap<>();

        @Override
        public void recordArrivalPrice(String orderId, BigDecimal price) {
            arrivals.put(orderId, price);
        }

        @Override
        public Optional<BigDecimal> arrivalPrice(String orderId) {
            return Optional.ofNullable(arrivals.get(orderId));
        }

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
        public boolean transitionIfCurrent(String orderId, OrderStatus expected, OrderStatus next,
                                           String reason, Instant now) {
            Order current = byId.get(orderId);
            if (current == null || current.status() != expected) {
                return false;
            }
            updateStatus(orderId, next, reason, now);
            return true;
        }

        @Override
        public void insertFill(Fill fill) {
            fills.add(fill);
        }

        @Override
        public Optional<Order> findByIdempotencyKey(String idempotencyKey) {
            return Optional.ofNullable(byKey.get(idempotencyKey));
        }

        @Override
        public Optional<Order> findById(String orderId) {
            return Optional.ofNullable(byId.get(orderId));
        }

        @Override
        public List<Order> findWorkingLimitOrders(String instrumentId) {
            return byId.values().stream()
                    .filter(o -> o.instrumentId().value().equals(instrumentId)
                            && o.status() == OrderStatus.ROUTED && o.type() == OrderType.LIMIT)
                    .toList();
        }

        @Override
        public List<Order> findAllWorkingLimitOrders() {
            return byId.values().stream()
                    .filter(o -> o.status() == OrderStatus.ROUTED && o.type() == OrderType.LIMIT)
                    .toList();
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
            new OrderService(store, new SimulatedExecutor(ExecutionCostSource.FREE), prices, publisher, PreTradeCheck.APPROVE_ALL);

    private NewOrder market(String key, Side side, String qty) {
        return new NewOrder(key, "ALPHA", "AAPL", side, OrderType.MARKET, new BigDecimal(qty), null);
    }

    private NewOrder limit(String key, String qty, String limitPrice, TimeInForce tif) {
        return new NewOrder(key, "ALPHA", "AAPL", Side.BUY, OrderType.LIMIT,
                new BigDecimal(qty), new BigDecimal(limitPrice), tif);
    }

    @Test
    void fillRecordsTcaAgainstTheArrivalPriceCapturedAtSubmit() {
        var recorded = new ArrayList<BigDecimal>();
        TcaRecorder recorder = (fill, arrival) -> recorded.add(arrival);
        var withTca = new OrderService(store, new SimulatedExecutor(ExecutionCostSource.FREE),
                prices, publisher, PreTradeCheck.APPROVE_ALL, recorder);

        prices.update("AAPL", new BigDecimal("151.00")); // arrival: what the desk saw at submit
        Order working = withTca.submit(limit("idem-tca", "10", "150.00", TimeInForce.GTC));
        assertEquals(OrderStatus.ROUTED, working.status());

        prices.update("AAPL", new BigDecimal("149.50"));
        withTca.onMark("AAPL", new BigDecimal("149.50")); // crosses much later
        assertEquals(1, recorded.size(), "the fill records TCA");
        assertEquals(0, new BigDecimal("151.00").compareTo(recorded.get(0)),
                "slippage measures against the SUBMIT-time mark, not the fill-time mark");
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
    void orderRejectedByThePreTradeGateNeverFillsAndCarriesTheReason() {
        prices.update("AAPL", new BigDecimal("150.00"));
        PreTradeCheck rejectAll = (book, instrument, qty) -> PreTradeCheck.Decision.reject("gross limit");
        var gated = new OrderService(store, new SimulatedExecutor(ExecutionCostSource.FREE), prices, publisher, rejectAll);

        Order result = gated.submit(market("idem-1", Side.BUY, "100"));

        assertEquals(OrderStatus.REJECTED, result.status());
        assertTrue(store.fills.isEmpty(), "a risk-rejected order must not fill");
        assertTrue(publisher.fillEvents.isEmpty());
        // Lifecycle stops at NEW → REJECTED (no ROUTED).
        assertEquals(List.of(OrderStatus.NEW, OrderStatus.REJECTED), publisher.orderEvents);
    }

    @Test
    void limitOrderThatIsNotMarketableStaysWorking() {
        prices.update("AAPL", new BigDecimal("151.00")); // above the buy limit

        Order result = service.submit(limit("idem-lim", "10", "150.00", TimeInForce.GTC));

        assertEquals(OrderStatus.ROUTED, result.status(), "unmarketable GTC limit stays working");
        assertTrue(store.fills.isEmpty());
        assertEquals(List.of(OrderStatus.NEW, OrderStatus.ROUTED), publisher.orderEvents);
    }

    @Test
    void iocLimitCancelsOnArrivalWhenNotMarketable() {
        prices.update("AAPL", new BigDecimal("151.00"));

        Order result = service.submit(limit("idem-ioc", "10", "150.00", TimeInForce.IOC));

        assertEquals(OrderStatus.CANCELLED, result.status(), "IOC dies instead of working");
        assertTrue(store.fills.isEmpty());
    }

    @Test
    void dayLimitWorksIntradayAndExpiresAtSessionClose() {
        prices.update("AAPL", new BigDecimal("151.00")); // above the buy limit — not marketable
        Order working = service.submit(limit("idem-day", "10", "150.00", TimeInForce.DAY));
        assertEquals(OrderStatus.ROUTED, working.status(), "unmarketable DAY limit works like GTC intraday");

        assertEquals(1, service.expireDayOrders(), "session close sweeps the working DAY order");
        assertEquals(OrderStatus.CANCELLED, store.byId.get(working.orderId()).status());

        service.onMark("AAPL", new BigDecimal("149.00")); // crosses — but the order is dead
        assertTrue(store.fills.isEmpty(), "an expired DAY order must never fill");
    }

    @Test
    void sessionCloseSweepsOnlyDayOrdersGtcSurvives() {
        prices.update("AAPL", new BigDecimal("151.00"));
        Order day = service.submit(limit("idem-day2", "10", "150.00", TimeInForce.DAY));
        Order gtc = service.submit(limit("idem-gtc2", "10", "150.00", TimeInForce.GTC));

        assertEquals(1, service.expireDayOrders(), "only the DAY order expires");
        assertEquals(OrderStatus.CANCELLED, store.byId.get(day.orderId()).status());
        assertEquals(OrderStatus.ROUTED, store.byId.get(gtc.orderId()).status(), "GTC works across sessions");

        service.onMark("AAPL", new BigDecimal("149.00"));
        assertEquals(1, store.fills.size(), "the surviving GTC still fills on a crossing mark");
    }

    @Test
    void workingLimitFillsWhenALaterMarkCrossesIt() {
        prices.update("AAPL", new BigDecimal("151.00"));
        Order working = service.submit(limit("idem-work", "10", "150.00", TimeInForce.GTC));
        assertEquals(OrderStatus.ROUTED, working.status());

        service.onMark("AAPL", new BigDecimal("149.90")); // mark crosses the buy limit

        assertEquals(OrderStatus.FILLED, store.byId.get(working.orderId()).status(),
                "mark-driven matching fills the working order (ADR-0025)");
        assertEquals(1, store.fills.size());
        assertEquals(0, new BigDecimal("150.00").compareTo(store.fills.get(0).price()),
                "limit fills AT the limit price");
    }

    @Test
    void repeatedCrossingMarksFillTheWorkingOrderExactlyOnce() {
        prices.update("AAPL", new BigDecimal("151.00"));
        service.submit(limit("idem-once", "10", "150.00", TimeInForce.GTC));

        service.onMark("AAPL", new BigDecimal("149.90"));
        service.onMark("AAPL", new BigDecimal("149.80")); // already filled — CAS must block

        assertEquals(1, store.fills.size(), "the CAS guard makes the fill exactly-once");
    }

    @Test
    void cancelStopsAWorkingOrderAndLaterMarksCannotFillIt() {
        prices.update("AAPL", new BigDecimal("151.00"));
        Order working = service.submit(limit("idem-cxl", "10", "150.00", TimeInForce.GTC));

        Order cancelled = service.cancel(working.orderId()).orElseThrow();
        assertEquals(OrderStatus.CANCELLED, cancelled.status());

        service.onMark("AAPL", new BigDecimal("149.90"));
        assertTrue(store.fills.isEmpty(), "a cancelled order must never fill");
    }

    @Test
    void cancellingAFilledOrderReportsFilledNotAnError() {
        prices.update("AAPL", new BigDecimal("150.00"));
        Order filled = service.submit(market("idem-fill", Side.BUY, "10"));

        Order result = service.cancel(filled.orderId()).orElseThrow();
        assertEquals(OrderStatus.FILLED, result.status(), "the fill won — report it, don't error");
    }

    @Test
    void routedOrderCarriesThroughTheOriginalInstrument() {
        prices.update("AAPL", new BigDecimal("150.00"));
        Order result = service.submit(market("idem-1", Side.SELL, "5"));

        assertSame(Side.SELL, result.side());
        assertEquals(new InstrumentId("AAPL"), result.instrumentId());
    }
}
