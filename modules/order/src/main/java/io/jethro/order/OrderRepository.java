package io.jethro.order;

import io.jethro.domain.BookId;
import io.jethro.domain.Fill;
import io.jethro.domain.InstrumentId;
import io.jethro.domain.Order;
import io.jethro.domain.OrderStatus;
import io.jethro.domain.OrderType;
import io.jethro.domain.Side;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
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
                                    order_type, quantity, limit_price, status, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (idempotency_key) do nothing
                """,
                order.orderId(), order.idempotencyKey(), order.bookId().value(),
                order.instrumentId().value(), order.side().name(), order.type().name(),
                order.quantity(), order.limitPrice().orElse(null), order.status().name(),
                Instant.now(), Instant.now());
        return rows == 1;
    }

    @Override
    public void updateStatus(String orderId, OrderStatus status, String reason, Instant now) {
        jdbc.update("update orders set status = ?, reason = ?, updated_at = ? where order_id = ?",
                status.name(), reason, now, orderId);
    }

    @Override
    public void insertFill(Fill fill) {
        jdbc.update("""
                insert into fills (fill_id, order_id, book_id, instrument_id, side, quantity, price, executed_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (fill_id) do nothing
                """,
                fill.fillId(), fill.orderId(), fill.bookId().value(), fill.instrumentId().value(),
                fill.side().name(), fill.quantity(), fill.price(), fill.executedAt());
    }

    @Override
    public Optional<Order> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.query("select * from orders where idempotency_key = ?",
                OrderRepository::mapOrder, idempotencyKey).stream().findFirst();
    }

    public List<OrderRow> recentOrders(int limit) {
        return jdbc.query("""
                select order_id, book_id, instrument_id, side, order_type, quantity,
                       limit_price, status, reason, created_at
                from orders order by created_at desc limit ?
                """, (rs, i) -> new OrderRow(
                rs.getString("order_id"), rs.getString("book_id"), rs.getString("instrument_id"),
                rs.getString("side"), rs.getString("order_type"), rs.getBigDecimal("quantity").toPlainString(),
                rs.getBigDecimal("limit_price") == null ? null : rs.getBigDecimal("limit_price").toPlainString(),
                rs.getString("status"), rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant().toEpochMilli()), limit);
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
                           String orderType, String quantity, String limitPrice, String status,
                           String reason, long createdAtMillis) {
    }

    public record FillRow(String fillId, String orderId, String instrumentId, String side,
                          String quantity, String price, long executedAtMillis) {
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
                OrderStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant());
    }
}
