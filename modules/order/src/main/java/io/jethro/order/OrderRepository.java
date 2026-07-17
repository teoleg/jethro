package io.jethro.order;

import io.jethro.domain.BookId;
import io.jethro.domain.Fill;
import io.jethro.domain.InstrumentId;
import io.jethro.domain.Order;
import io.jethro.domain.OrderStatus;
import io.jethro.domain.OrderType;
import io.jethro.domain.Side;
import io.jethro.domain.TimeInForce;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** Postgres persistence for orders and fills (the order module owns its state, ADR-0003). */
public class OrderRepository implements OrderStore {

    private final JdbcTemplate jdbc;

    public OrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a NEW order unless its idempotency key already exists (invariant 6).
     * Returns true if this call created the row, false if the key was already present.
     */
    @Override
    public boolean insertIfAbsent(Order order, Instant now) {
        int rows = jdbc.update("""
                insert into orders (order_id, idempotency_key, book_id, instrument_id, side,
                                    order_type, quantity, limit_price, time_in_force, status,
                                    created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (idempotency_key) do nothing
                """,
                order.orderId(), order.idempotencyKey(), order.bookId().value(),
                order.instrumentId().value(), order.side().name(), order.type().name(),
                order.quantity(), order.limitPrice().orElse(null), order.timeInForce().name(),
                order.status().name(), ts(order.createdAt()), ts(now));
        return rows == 1;
    }

    @Override
    public void updateStatus(String orderId, OrderStatus status, String reason, Instant now) {
        jdbc.update("update orders set status = ?, reason = ?, updated_at = ? where order_id = ?",
                status.name(), reason, ts(now), orderId);
    }

    /** CAS transition (ADR-0025): the WHERE-status guard is what makes fills exactly-once
     *  when submit-time execution, mark-driven matching and cancel race for one order. */
    @Override
    public boolean transitionIfCurrent(String orderId, OrderStatus expected, OrderStatus next,
                                       String reason, Instant now) {
        int rows = jdbc.update(
                "update orders set status = ?, reason = ?, updated_at = ? where order_id = ? and status = ?",
                next.name(), reason, ts(now), orderId, expected.name());
        return rows == 1;
    }

    @Override
    public void insertFill(Fill fill) {
        jdbc.update("""
                insert into fills (fill_id, order_id, book_id, instrument_id, side, quantity, price, fee, executed_at, feed_mode)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (fill_id) do nothing
                """,
                fill.fillId(), fill.orderId(), fill.bookId().value(), fill.instrumentId().value(),
                fill.side().name(), fill.quantity(), fill.price(), fill.fee(), ts(fill.executedAt()),
                io.jethro.messaging.Provenance.mode().name()); // ADR-0029: tag the fill's feed mode
    }

    /**
     * PgJDBC cannot infer a SQL type for a bare {@link Instant}; {@link OffsetDateTime}
     * is the unambiguous binding for a {@code timestamptz} column.
     */
    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    @Override
    public Optional<Order> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.query("select * from orders where idempotency_key = ?",
                OrderRepository::mapOrder, idempotencyKey).stream().findFirst();
    }

    @Override
    public Optional<Order> findById(String orderId) {
        return jdbc.query("select * from orders where order_id = ?",
                OrderRepository::mapOrder, orderId).stream().findFirst();
    }

    @Override
    public List<Order> findWorkingLimitOrders(String instrumentId) {
        return jdbc.query("""
                select * from orders
                where instrument_id = ? and status = 'ROUTED' and order_type = 'LIMIT'
                order by created_at
                """, OrderRepository::mapOrder, instrumentId);
    }

    @Override
    public void recordArrivalPrice(String orderId, java.math.BigDecimal price) {
        jdbc.update("update orders set arrival_price = ? where order_id = ?", price, orderId);
    }

    @Override
    public Optional<java.math.BigDecimal> arrivalPrice(String orderId) {
        var rows = jdbc.query("select arrival_price from orders where order_id = ?",
                (rs, i) -> rs.getBigDecimal("arrival_price"), orderId);
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    @Override
    public List<Order> findAllWorkingLimitOrders() {
        return jdbc.query(
                "select * from orders where status = 'ROUTED' and order_type = 'LIMIT' order by created_at",
                OrderRepository::mapOrder);
    }

    /** Orders created on {@code day} (server zone), newest first, paginated. */
    public List<OrderRow> ordersForDay(java.time.LocalDate day, int offset, int limit) {
        OffsetDateTime start = day.atStartOfDay(java.time.ZoneId.systemDefault()).toOffsetDateTime();
        OffsetDateTime end = start.plusDays(1);
        return jdbc.query("""
                select order_id, book_id, instrument_id, side, order_type, quantity,
                       limit_price, time_in_force, status, reason, created_at
                from orders where created_at >= ? and created_at < ?
                order by created_at desc offset ? limit ?
                """, OrderRepository::mapRow, start, end, offset, limit);
    }

    /** Count of orders created on {@code day} (server zone). */
    public long countOrdersForDay(java.time.LocalDate day) {
        OffsetDateTime start = day.atStartOfDay(java.time.ZoneId.systemDefault()).toOffsetDateTime();
        Long count = jdbc.queryForObject(
                "select count(*) from orders where created_at >= ? and created_at < ?",
                Long.class, start, start.plusDays(1));
        return count == null ? 0 : count;
    }

    public List<OrderRow> recentOrders(int limit) {
        return jdbc.query("""
                select order_id, book_id, instrument_id, side, order_type, quantity,
                       limit_price, time_in_force, status, reason, created_at
                from orders order by created_at desc limit ?
                """, OrderRepository::mapRow, limit);
    }

    public List<FillRow> recentFills(int limit) {
        return jdbc.query("""
                select fill_id, order_id, instrument_id, side, quantity, price, executed_at
                from fills order by executed_at desc limit ?
                """, (rs, i) -> new FillRow(
                rs.getString("fill_id"), rs.getString("order_id"), rs.getString("instrument_id"),
                rs.getString("side"), rs.getBigDecimal("quantity").toPlainString(),
                rs.getBigDecimal("price").toPlainString(),
                rs.getTimestamp("executed_at").toInstant().toEpochMilli()), limit);
    }

    /** Boundary DTOs: decimals as strings (invariant 1 — never a JS float). */
    public record OrderRow(String orderId, String bookId, String instrumentId, String side,
                           String orderType, String quantity, String limitPrice, String timeInForce,
                           String status, String reason, long createdAtMillis) {
    }

    public record FillRow(String fillId, String orderId, String instrumentId, String side,
                          String quantity, String price, long executedAtMillis) {
    }

    private static OrderRow mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new OrderRow(
                rs.getString("order_id"), rs.getString("book_id"), rs.getString("instrument_id"),
                rs.getString("side"), rs.getString("order_type"), rs.getBigDecimal("quantity").toPlainString(),
                rs.getBigDecimal("limit_price") == null ? null : rs.getBigDecimal("limit_price").toPlainString(),
                rs.getString("time_in_force"), rs.getString("status"), rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant().toEpochMilli());
    }

    private static Order mapOrder(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        var limit = rs.getBigDecimal("limit_price");
        return new Order(
                rs.getString("order_id"),
                rs.getString("idempotency_key"),
                new BookId(rs.getString("book_id")),
                new InstrumentId(rs.getString("instrument_id")),
                Side.valueOf(rs.getString("side")),
                OrderType.valueOf(rs.getString("order_type")),
                rs.getBigDecimal("quantity"),
                Optional.ofNullable(limit),
                TimeInForce.valueOf(rs.getString("time_in_force")),
                OrderStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant());
    }
}
