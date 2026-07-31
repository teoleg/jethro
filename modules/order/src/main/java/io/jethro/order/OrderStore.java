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

    /**
     * Inserts a NEW order unless its idempotency key exists; true if this call created it.
     *
     * <p>{@code originReason} (ADR-0134) is the originating trigger — why the desk wanted the trade.
     * It is written once, with the row, and is never touched by a status transition, so it survives
     * the NEW → ROUTED → FILLED path that has no status reason to record. Persistence-only, like the
     * parent linkage below: it never enters the {@link Order} domain record.
     */
    boolean insertIfAbsent(Order order, String originReason, Instant now);

    /**
     * Inserts a NEW child slice linked to its parent order (the ADV auto-slicer). The parent
     * linkage is persistence-only — it never enters the {@link Order} domain record. Default
     * ignores the parent id (fakes keep working); the JDBC store persists it. A slice inherits
     * its parent's origin (ADR-0134): the desk wanted it for the same reason.
     */
    default boolean insertChildIfAbsent(Order order, String parentOrderId, String originReason,
                                        Instant now) {
        return insertIfAbsent(order, originReason, now);
    }

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

    /** Records the mid at order submission (the TCA arrival/decision price, ADR-0025). */
    void recordArrivalPrice(String orderId, java.math.BigDecimal price);

    /** The arrival price captured at submit; empty when no mark existed then. */
    Optional<java.math.BigDecimal> arrivalPrice(String orderId);
}
