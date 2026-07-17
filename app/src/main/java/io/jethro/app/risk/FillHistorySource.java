package io.jethro.app.risk;

import io.jethro.domain.BookId;
import io.jethro.domain.Fill;
import io.jethro.domain.InstrumentId;
import io.jethro.domain.Side;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

/**
 * Reads the complete fill history from the Postgres {@code fills} table — the actual
 * source of truth for positions (invariant 3). The risk projection seeds from THIS at
 * boot, not from a topic replay: the {@code fills} topic is retention-bounded (an
 * unconfigured topic keeps the broker default, ~7 days), so rebuilding positions from the
 * topic would silently truncate history once the platform has run longer than the window.
 * The table never expires, so it is the authoritative rebuild source; the topic then only
 * supplies live increments (consumed from LATEST). Reading a shared table is not a module
 * dependency — invariant 3 explicitly makes risk-pnl a projector of {@code fills}.
 */
public interface FillHistorySource {

    /** Every persisted fill, oldest first (a projection replay must be time-ordered). */
    List<Fill> allFills();

    /** Empty source — no persistence (tests, kafka-only profiles): projection seeds from nothing. */
    FillHistorySource NONE = List::of;

    /** JDBC-backed reader over the {@code fills} table (columns per V3/V24). */
    static FillHistorySource jdbc(JdbcTemplate jdbc) {
        return () -> jdbc.query("""
                select fill_id, order_id, book_id, instrument_id, side, quantity, price, fee, executed_at
                from fills where feed_mode = ? order by executed_at, fill_id
                """, (rs, i) -> new Fill(
                rs.getString("fill_id"),
                rs.getString("order_id"),
                new BookId(rs.getString("book_id")),
                new InstrumentId(rs.getString("instrument_id")),
                Side.valueOf(rs.getString("side")),
                rs.getBigDecimal("quantity"),
                rs.getBigDecimal("price"),
                rs.getBigDecimal("fee") != null ? rs.getBigDecimal("fee") : java.math.BigDecimal.ZERO,
                toInstant(rs.getTimestamp("executed_at"))),
                io.jethro.messaging.Provenance.mode().name());
    }

    private static Instant toInstant(java.sql.Timestamp ts) {
        return ts != null ? ts.toInstant() : Instant.EPOCH;
    }
}
