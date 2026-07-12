package io.jethro.order;

import io.jethro.domain.BookId;
import io.jethro.domain.Fill;
import io.jethro.domain.InstrumentId;
import io.jethro.domain.Order;
import io.jethro.domain.OrderStatus;
import io.jethro.domain.OrderType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Order lifecycle orchestration (ADR-0008 state machine) with simulated execution.
 * Flow: dedupe → persist NEW → ROUTED → simulated fill (FILLED) or REJECTED (no market
 * data) or stays working (LIMIT not marketable). Every transition emits an event
 * (orders.events); fills go to `fills`, the source of truth for positions (invariant 3).
 */
public final class OrderService {

    private final OrderStore store;
    private final SimulatedExecutor executor;
    private final LastPriceCache prices;
    private final OrderEventPublisher publisher;

    public OrderService(OrderStore store, SimulatedExecutor executor,
                        LastPriceCache prices, OrderEventPublisher publisher) {
        this.store = store;
        this.executor = executor;
        this.prices = prices;
        this.publisher = publisher;
    }

    /** Submits an order. Idempotent on {@link NewOrder#idempotencyKey()}. */
    public Order submit(NewOrder command) {
        // Invariant 6: a re-submitted command returns the existing order, never a second one.
        Optional<Order> existing = store.findByIdempotencyKey(command.idempotencyKey());
        if (existing.isPresent()) {
            return existing.get();
        }

        Order order = new Order(
                "ord-" + UUID.randomUUID(),
                command.idempotencyKey(),
                new BookId(command.bookId()),
                new InstrumentId(command.instrumentId()),
                command.side(),
                command.type(),
                command.quantity(),
                Optional.ofNullable(command.limitPrice()),
                OrderStatus.NEW,
                Instant.now());

        if (!store.insertIfAbsent(order, Instant.now())) {
            // Lost an idempotency race — return the row the winner inserted.
            return store.findByIdempotencyKey(command.idempotencyKey()).orElseThrow();
        }
        publisher.publishOrderEvent(order, null);

        order = transition(order, OrderStatus.ROUTED, null);

        BigDecimal mark = prices.lastPrice(order.instrumentId()).orElse(null);
        Optional<Fill> fill = executor.tryExecute(order, mark);
        if (fill.isPresent()) {
            store.insertFill(fill.get());
            publisher.publishFill(fill.get());
            order = transition(order, OrderStatus.FILLED, null);
        } else if (mark == null) {
            order = transition(order, OrderStatus.REJECTED,
                    "no market data for " + order.instrumentId().value());
        }
        // else: LIMIT not marketable — order stays ROUTED (working) until a matching mark.
        return order;
    }

    private Order transition(Order order, OrderStatus next, String reason) {
        Order updated = order.withStatus(next); // validates the transition (ADR-0008)
        store.updateStatus(updated.orderId(), next, reason, Instant.now());
        publisher.publishOrderEvent(updated, reason);
        return updated;
    }

    /** Guard exposed for callers that pre-validate MARKET orders need a price feed. */
    public boolean requiresMark(OrderType type) {
        return SimulatedExecutor.requiresMark(type);
    }
}
