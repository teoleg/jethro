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
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
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
    private final TcaRecorder tca;

    /** instrumentId → working (ROUTED LIMIT) order ids — the mark path's cheap gate. */
    private final Map<String, Set<String>> workingByInstrument = new ConcurrentHashMap<>();

    public OrderService(OrderStore store, SimulatedExecutor executor, LastPriceCache prices,
                        OrderEventPublisher publisher, PreTradeCheck preTradeCheck) {
        this(store, executor, prices, publisher, preTradeCheck, TcaRecorder.NONE);
    }

    /** With TCA (ADR-0025): every fill's slippage vs its arrival price is recorded. */
    public OrderService(OrderStore store, SimulatedExecutor executor, LastPriceCache prices,
                        OrderEventPublisher publisher, PreTradeCheck preTradeCheck, TcaRecorder tca) {
        this.store = store;
        this.executor = executor;
        this.prices = prices;
        this.publisher = publisher;
        this.preTradeCheck = preTradeCheck;
        this.tca = tca;
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

        // ADV participation cap (ADR-0025 + auto-slicer): a risk-ADDING order that would be an
        // outsized fraction of the day's volume is SPLIT into child slices rather than rejected;
        // a risk-REDUCING exit is never capped (a desk must always be able to get out of a
        // position, whatever the day's volume). The deterministic pre-trade exposure gate
        // (ADR-0018) runs per routed order inside {@link #routeApproveAndFill}.
        BigDecimal preMark = prices.lastPrice(order.instrumentId()).orElse(null);
        BigDecimal signedQty = order.side().signed(order.quantity());
        if (executor.participationRejection(order, preMark).isPresent()
                && !preTradeCheck.reducesRisk(order.bookId(), order.instrumentId(), signedQty)) {
            return sliceOverCap(order, preMark);
        }
        return routeApproveAndFill(order, preMark);
    }

    /**
     * Routes an already-inserted order through the deterministic pre-trade exposure gate
     * (ADR-0018) and execution. Shared by the normal submit path and by every child slice.
     * @return the order after the attempt: FILLED, REJECTED, CANCELLED (IOC), or working.
     */
    private Order routeApproveAndFill(Order order, BigDecimal preMark) {
        PreTradeCheck.Decision gate = preTradeCheck.check(
                order.bookId(), order.instrumentId(), order.side().signed(order.quantity()));
        if (!gate.approved()) {
            return transition(order, OrderStatus.REJECTED, gate.reason());
        }
        order = transition(order, OrderStatus.ROUTED, null);
        if (preMark != null) {
            // TCA arrival/decision price (ADR-0025): captured BEFORE any fill, so a worked
            // LIMIT that fills much later still measures against what the desk saw at submit.
            store.recordArrivalPrice(order.orderId(), preMark);
        }
        Order filled = tryFill(order, preMark);
        if (filled != null) {
            return filled;
        }
        if (preMark == null) {
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

    /** The most child slices one over-cap order may be split into before it's simply rejected
     *  as too large for the day's volume — bounds a tiny measured ADV from spawning thousands. */
    private static final int MAX_SLICES = 50;

    /**
     * Splits a risk-adding order that exceeds the ADV participation cap into child slices, each
     * at or below the cap, and routes every one (ADR-0025 auto-slicer). The parent order is
     * CANCELLED with its child ids in the reason; each child carries {@code parent_order_id}
     * back to the parent and shows as a "split" order. Rejected only when a single minimum slice
     * still can't fit the cap, or it would take more than {@value #MAX_SLICES} slices — in which
     * case the order is genuinely too big for the day's volume, said plainly.
     */
    private Order sliceOverCap(Order parent, BigDecimal preMark) {
        Optional<BigDecimal> maxQtyOpt = executor.maxQuantityUnderCap(parent, preMark);
        if (maxQtyOpt.isEmpty()) {
            return transition(parent, OrderStatus.REJECTED,
                    "exceeds the ADV participation cap and cannot be sliced");
        }
        BigDecimal maxQty = maxQtyOpt.get();
        BigDecimal total = parent.quantity();
        BigDecimal sliceCount = total.divide(maxQty, 0, RoundingMode.CEILING);
        if (sliceCount.compareTo(BigDecimal.valueOf(MAX_SLICES)) > 0) {
            return transition(parent, OrderStatus.REJECTED,
                    "too large for the day's volume — would need " + sliceCount.toBigInteger()
                            + " slices to fit the ADV cap (max " + MAX_SLICES + ")");
        }
        int n = sliceCount.intValue();
        List<String> childIds = new ArrayList<>(n);
        BigDecimal remaining = total;
        for (int i = 0; i < n; i++) {
            BigDecimal qty = remaining.min(maxQty);
            remaining = remaining.subtract(qty);
            Order child = new Order(
                    "ord-" + UUID.randomUUID(),
                    parent.idempotencyKey() + ":slice:" + i,
                    parent.bookId(), parent.instrumentId(), parent.side(), parent.type(), qty,
                    parent.limitPrice(), parent.timeInForce(), OrderStatus.NEW, Instant.now());
            if (store.insertChildIfAbsent(child, parent.orderId(), Instant.now())) {
                publisher.publishOrderEvent(child, null);
                routeApproveAndFill(child, preMark);
                childIds.add(child.orderId());
            } else {
                // Idempotency race (parent command resubmitted mid-slice) — reuse the winner.
                store.findByIdempotencyKey(child.idempotencyKey())
                        .ifPresent(existing -> childIds.add(existing.orderId()));
            }
        }
        log.info("sliced {} {} {} into {} child order(s) to fit the ADV cap",
                parent.side(), total.toPlainString(), parent.instrumentId().value(), n);
        return transition(parent, OrderStatus.CANCELLED,
                "split into " + n + " slices to fit the ADV cap: " + String.join(", ", childIds));
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
        return cancel(orderId, "cancelled by user");
    }

    /**
     * As {@link #cancel(String)}, with the reason recorded on the order and published with the
     * event. A caller that cancels for its own reasons — a quoting loop replacing a stale passive
     * order, say (ADR-0084) — must be able to say so, or the audit trail reads as an operator
     * action that never happened.
     */
    public Optional<Order> cancel(String orderId, String reason) {
        Optional<Order> found = store.findById(orderId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Order order = found.get();
        if (order.status().isTerminal()) {
            return Optional.of(order); // already done — report the terminal state, don't error
        }
        if (store.transitionIfCurrent(orderId, OrderStatus.ROUTED, OrderStatus.CANCELLED,
                reason, Instant.now())) {
            indexRemove(order);
            Order cancelled = order.withStatus(OrderStatus.CANCELLED);
            publisher.publishOrderEvent(cancelled, reason);
            log.info("cancelled {} ({} {} {}) — {}", orderId,
                    order.side(), order.quantity().toPlainString(), order.instrumentId().value(), reason);
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
        // The quote riding with the mark (bid/ask), when the feed carries one (ADR-0025).
        LastPriceCache.Quote quote = prices.quote(order.instrumentId()).orElse(null);
        Optional<Fill> fill = executor.tryExecute(order, mark,
                quote != null ? quote.bid() : null, quote != null ? quote.ask() : null);
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
        // TCA (ADR-0025): slippage vs the arrival price captured at submit. Measurement only —
        // a missing arrival (no mark at submit) records nothing, never blocks the fill.
        store.arrivalPrice(order.orderId()).ifPresent(arrival -> tca.record(fill.get(), arrival));
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

    /**
     * Every working (ROUTED LIMIT) order across all instruments — what a caller that POSTS
     * passively needs in order to replace its own stale intent (ADR-0084). Read-only.
     */
    public List<Order> workingOrders() {
        return store.findAllWorkingLimitOrders();
    }

    /**
     * The mark this module would stamp as an order's TCA arrival price right now, or empty when
     * no mark for the instrument has arrived on {@code md.marks} yet.
     *
     * <p>Exposed so a caller that posts a passive LIMIT can price it off the SAME mark the
     * arrival price is taken from. Pricing it off any other cache — trading-core's in-process
     * one, say — would make measured slippage on a passive fill the difference between two
     * clocks rather than a cost, and that measurement feeds the edge gate.
     */
    public Optional<BigDecimal> lastPrice(String instrumentId) {
        return prices.lastPrice(new InstrumentId(instrumentId));
    }

    /** Guard exposed for callers that pre-validate MARKET orders need a price feed. */
    public boolean requiresMark(OrderType type) {
        return SimulatedExecutor.requiresMark(type);
    }
}
