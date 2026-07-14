package io.jethro.order;

import io.jethro.domain.BookId;
import io.jethro.domain.Fill;
import io.jethro.domain.InstrumentId;
import io.jethro.domain.Order;
import io.jethro.domain.OrderStatus;
import io.jethro.domain.OrderType;
import io.jethro.domain.TimeInForce;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Order lifecycle orchestration (ADR-0008 state machine) with simulated execution and a
 * complete working-order lifecycle (ADR-0025). Flow: dedupe → persist NEW → ROUTED →
 * simulated fill (FILLED) or REJECTED (no market data) — or, for an unmarketable LIMIT:
 * IOC cancels on arrival; GTC and DAY stay working and are retried on every new mark for
 * their instrument ({@link #onMark}) until filled or cancelled ({@link #cancel}), with DAY
 * additionally swept at session close ({@link #expireDayOrders}, ADR-0027 calendar).
 *
 * <p><b>Exactly-once fills under racing paths:</b> submit-time execution, mark-driven
 * matching and cancel can all target the same order. Two guards: (1) the fill path is
 * {@code synchronized} (one JVM owns orders — ADR-0015); (2) every contested transition is
 * a DB compare-and-set ({@link OrderStore#transitionIfCurrent}) and the fill row is written
 * only by the CAS winner — so even a future second process cannot double-fill. The CAS runs
 * BEFORE the fill insert: a crash in between leaves a FILLED order missing its fill (visible,
 * reconcilable) rather than the reverse, which could double-count positions (invariant 3).
 *
 * <p>A per-instrument index of working-order ids keeps the mark path cheap: {@link #onMark}
 * touches the database only when this instrument actually has working orders.
 */
public final class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderStore store;
    private final SimulatedExecutor executor;
    private final LastPriceCache prices;
    private final OrderEventPublisher publisher;
    private final PreTradeCheck preTradeCheck;

    /** instrumentId → working (ROUTED LIMIT) order ids — the mark path's cheap gate. */
    private final Map<String, Set<String>> workingByInstrument = new ConcurrentHashMap<>();

    public OrderService(OrderStore store, SimulatedExecutor executor, LastPriceCache prices,
                        OrderEventPublisher publisher, PreTradeCheck preTradeCheck) {
        this.store = store;
        this.executor = executor;
        this.prices = prices;
        this.publisher = publisher;
        this.preTradeCheck = preTradeCheck;
        // Working orders survive a restart (they're rows, not memory) — reseed the index.
        for (Order working : store.findAllWorkingLimitOrders()) {
            indexAdd(working);
        }
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
                command.timeInForce(),
                OrderStatus.NEW,
                Instant.now());

        if (!store.insertIfAbsent(order, Instant.now())) {
            // Lost an idempotency race — return the row the winner inserted.
            return store.findByIdempotencyKey(command.idempotencyKey()).orElseThrow();
        }
        publisher.publishOrderEvent(order, null);

        // Deterministic pre-trade risk gate (ADR-0018): reject before routing if the
        // order would push the book over an exposure limit.
        PreTradeCheck.Decision gate = preTradeCheck.check(
                order.bookId(), order.instrumentId(), order.side().signed(order.quantity()));
        if (!gate.approved()) {
            return transition(order, OrderStatus.REJECTED, gate.reason());
        }

        order = transition(order, OrderStatus.ROUTED, null);

        BigDecimal mark = prices.lastPrice(order.instrumentId()).orElse(null);
        Order filled = tryFill(order, mark);
        if (filled != null) {
            return filled;
        }
        if (mark == null) {
            return transition(order, OrderStatus.REJECTED,
                    "no market data for " + order.instrumentId().value());
        }
        // LIMIT not marketable: IOC dies on arrival; GTC and DAY go to work and wait for marks
        // (DAY is swept at session close by {@link #expireDayOrders} — the ADR-0027 calendar).
        if (order.timeInForce() == TimeInForce.IOC) {
            return transition(order, OrderStatus.CANCELLED, "IOC — not marketable on arrival");
        }
        indexAdd(order);
        return order;
    }

    /**
     * Expires every working DAY order at session close (ADR-0027): the app's EOD boundary
     * calls this when the calendar rolls. Same CAS as {@link #cancel} — a racing fill wins
     * cleanly. A DAY order that outlived a crashed session is swept at the NEXT boundary
     * (late, disclosed in the reason), never silently promoted to GTC.
     * @return how many orders were expired.
     */
    public int expireDayOrders() {
        int expired = 0;
        for (Order order : store.findAllWorkingLimitOrders()) {
            if (order.timeInForce() != TimeInForce.DAY) {
                continue;
            }
            if (store.transitionIfCurrent(order.orderId(), OrderStatus.ROUTED, OrderStatus.CANCELLED,
                    "DAY order expired at session close", Instant.now())) {
                indexRemove(order);
                publisher.publishOrderEvent(order.withStatus(OrderStatus.CANCELLED),
                        "DAY order expired at session close");
                expired++;
            }
        }
        if (expired > 0) {
            log.info("session close: expired {} DAY order(s)", expired);
        }
        return expired;
    }

    /**
     * Mark-driven matching (ADR-0025): retries the instrument's working LIMIT orders against
     * the new mark. Called by the market-data consumer on every mark; the in-memory index
     * makes the no-working-orders case (almost every mark) free.
     */
    public void onMark(String instrumentId, BigDecimal mark) {
        Set<String> ids = workingByInstrument.get(instrumentId);
        if (ids == null || ids.isEmpty()) {
            return;
        }
        for (Order order : store.findWorkingLimitOrders(instrumentId)) {
            tryFill(order, mark);
        }
    }

    /**
     * Cancels a working order (ADR-0025). CAS ROUTED→CANCELLED: a concurrent fill wins
     * cleanly (you cannot cancel what already filled) and this reports the final state.
     * @return the order after the attempt, or empty if the id is unknown.
     */
    public Optional<Order> cancel(String orderId) {
        Optional<Order> found = store.findById(orderId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Order order = found.get();
        if (order.status().isTerminal()) {
            return Optional.of(order); // already done — report the terminal state, don't error
        }
        if (store.transitionIfCurrent(orderId, OrderStatus.ROUTED, OrderStatus.CANCELLED,
                "cancelled by user", Instant.now())) {
            indexRemove(order);
            Order cancelled = order.withStatus(OrderStatus.CANCELLED);
            publisher.publishOrderEvent(cancelled, "cancelled by user");
            log.info("cancelled {} ({} {} {})", orderId,
                    order.side(), order.quantity().toPlainString(), order.instrumentId().value());
            return Optional.of(cancelled);
        }
        return store.findById(orderId); // lost to a concurrent fill/cancel — report what won
    }

    /**
     * The single fill path for BOTH submit-time execution and mark-driven matching:
     * synchronized + CAS so an order fills exactly once. @return the FILLED order, or null
     * if it couldn't fill now (not marketable / no mark / lost the CAS).
     */
    private synchronized Order tryFill(Order order, BigDecimal mark) {
        Optional<Fill> fill = executor.tryExecute(order, mark);
        if (fill.isEmpty()) {
            return null;
        }
        // CAS first (see class doc for the crash-direction rationale), fill row second.
        if (!store.transitionIfCurrent(order.orderId(), OrderStatus.ROUTED, OrderStatus.FILLED,
                null, Instant.now())) {
            return null; // another path filled/cancelled it first — do NOT write a fill
        }
        indexRemove(order);
        store.insertFill(fill.get());
        publisher.publishFill(fill.get());
        Order filled = order.withStatus(OrderStatus.FILLED);
        publisher.publishOrderEvent(filled, null);
        return filled;
    }

    private Order transition(Order order, OrderStatus next, String reason) {
        Order updated = order.withStatus(next); // validates the transition (ADR-0008)
        store.updateStatus(updated.orderId(), next, reason, Instant.now());
        publisher.publishOrderEvent(updated, reason);
        return updated;
    }

    private void indexAdd(Order order) {
        workingByInstrument
                .computeIfAbsent(order.instrumentId().value(), k -> ConcurrentHashMap.newKeySet())
                .add(order.orderId());
    }

    private void indexRemove(Order order) {
        Set<String> ids = workingByInstrument.get(order.instrumentId().value());
        if (ids != null) {
            ids.remove(order.orderId());
        }
    }

    /** Working (ROUTED LIMIT) orders on one instrument — for tests/observability. */
    List<Order> workingOrders(String instrumentId) {
        return store.findWorkingLimitOrders(instrumentId);
    }

    /** Guard exposed for callers that pre-validate MARKET orders need a price feed. */
    public boolean requiresMark(OrderType type) {
        return SimulatedExecutor.requiresMark(type);
    }
}
