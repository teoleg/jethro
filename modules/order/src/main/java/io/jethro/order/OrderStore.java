package io.jethro.order;

import io.jethro.domain.Fill;
import io.jethro.domain.Order;
import io.jethro.domain.OrderStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Write-path persistence port used by {@link OrderService} — an interface so the
 * service is testable without a database. {@link OrderRepository} is the JDBC
 * implementation.
 */
public interface OrderStore {

    /** Inserts a NEW order unless its idempotency key exists; true if this call created it. */
    boolean insertIfAbsent(Order order, Instant now);

    void updateStatus(String orderId, OrderStatus status, String reason, Instant now);

    /**
     * Compare-and-set status transition (ADR-0025): moves the order to {@code next} only if
     * it is still in {@code expected}; returns whether THIS call won. The guard that makes a
     * working order fill exactly once when submit-time execution, mark-driven matching and
     * cancel race — the loser sees {@code false} and must not act.
     */
    boolean transitionIfCurrent(String orderId, OrderStatus expected, OrderStatus next,
                                String reason, Instant now);

    void insertFill(Fill fill);

    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    Optional<Order> findById(String orderId);

    /** All working (ROUTED) LIMIT orders on one instrument — the matching set for a new mark. */
    List<Order> findWorkingLimitOrders(String instrumentId);

    /** Every working LIMIT order — loaded once at startup to seed the in-memory index. */
    List<Order> findAllWorkingLimitOrders();
}
