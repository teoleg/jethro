package io.jethro.order;

import io.jethro.domain.Fill;
import io.jethro.domain.Order;
import io.jethro.domain.OrderStatus;

import java.time.Instant;
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

    void insertFill(Fill fill);

    Optional<Order> findByIdempotencyKey(String idempotencyKey);
}
