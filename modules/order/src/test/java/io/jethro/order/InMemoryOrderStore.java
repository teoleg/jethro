package io.jethro.order;

import io.jethro.domain.Fill;
import io.jethro.domain.Order;
import io.jethro.domain.OrderStatus;
import io.jethro.domain.OrderType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory {@link OrderStore} honouring the JDBC store's semantics, including the CAS
 * transition. Shared by the lifecycle tests ({@code OrderServiceTest}) and the perf
 * guardrail ({@code OrderPathPerfTest}) so both exercise identical store behaviour.
 */
final class InMemoryOrderStore implements OrderStore {

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
