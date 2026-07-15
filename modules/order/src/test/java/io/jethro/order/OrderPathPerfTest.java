package io.jethro.order;

import io.jethro.domain.Fill;
import io.jethro.domain.Order;
import io.jethro.domain.OrderStatus;
import io.jethro.domain.OrderType;
import io.jethro.domain.Side;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Throughput guardrail for the order orchestration path (see
 * docs/architecture/perf-budgets.md). In production this path is Postgres-bound; measuring
 * it against the in-memory store isolates the ORCHESTRATION cost — state machine, CAS,
 * arrival-price capture, simulated execution, publish — which must stay negligible next to
 * a single database round trip (~1 ms). The floor is set orders of magnitude under a
 * healthy build so CI scheduler noise never flakes it, while an accidental O(n) scan over
 * all orders per submit (the realistic regression as books grow) still fails loudly.
 */
class OrderPathPerfTest {

    private static final int WARMUP_ORDERS = 2_000;
    private static final int MEASURED_ORDERS = 10_000;
    private static final long FLOOR_ORDERS_PER_SEC = 500;

    /** No-op publisher: the perf subject is orchestration, not the (mocked) broker client. */
    private static final OrderEventPublisher NOOP_PUBLISHER = new OrderEventPublisher() {
        @Override
        public void publishOrderEvent(Order order, String reason) {
        }

        @Override
        public void publishFill(Fill fill) {
        }
    };

    @Test
    void submitToFillOrchestrationStaysNegligibleNextToADatabaseRoundTrip() {
        var store = new InMemoryOrderStore();
        var prices = new LastPriceCache();
        var service = new OrderService(store, new SimulatedExecutor(ExecutionCostSource.FREE),
                prices, NOOP_PUBLISHER, PreTradeCheck.APPROVE_ALL);
        prices.update("AAPL", new BigDecimal("190.000000"));

        for (int i = 0; i < WARMUP_ORDERS; i++) {
            service.submit(order("w" + i));
        }
        long start = System.nanoTime();
        for (int i = 0; i < MEASURED_ORDERS; i++) {
            Order result = service.submit(order("m" + i));
            assertEquals(OrderStatus.FILLED, result.status());
        }
        long nanos = System.nanoTime() - start;

        long ordersPerSec = MEASURED_ORDERS * 1_000_000_000L / Math.max(1, nanos);
        double microsPerOrder = nanos / 1e3 / MEASURED_ORDERS;
        System.out.printf("[perf] order submit→fill (in-memory store): %,d orders/s, %.1f µs/order (floor %,d/s)%n",
                ordersPerSec, microsPerOrder, FLOOR_ORDERS_PER_SEC);
        assertTrue(ordersPerSec > FLOOR_ORDERS_PER_SEC,
                "order orchestration degraded to " + ordersPerSec + " orders/s in-memory — floor "
                        + FLOOR_ORDERS_PER_SEC + "/s is orders of magnitude under a healthy build");
        assertEquals(WARMUP_ORDERS + MEASURED_ORDERS, store.fills.size(), "every submit filled exactly once");
    }

    private static NewOrder order(String key) {
        return new NewOrder(key, "ALPHA", "AAPL", Side.BUY, OrderType.MARKET, BigDecimal.TEN, null);
    }
}
