package io.jethro.app.hypothesis;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Persists executed AI hypotheses and their outcomes (ADR-0022/0027). {@link #NOOP} is used
 * when persistence is off — the lifecycle still keeps them in-memory for the session, they
 * just don't survive a restart; {@link Jdbc} writes to and reads from {@code hypothesis_record}.
 */
public interface HypothesisRecordStore {

    void save(HypothesisRecord record);

    /** Scores a record at horizon expiry (ADR-0027). Idempotent: only an OPEN record scores. */
    void markOutcome(String id, String outcome, BigDecimal outcomePnl, BigDecimal exitPrice, Instant closedAt);

    /** Most-recent executed hypotheses (newest first), up to {@code limit}, outcomes included. */
    List<HypothesisRecord> recent(int limit);

    /** No-op store (persistence disabled): nothing survives a restart. */
    HypothesisRecordStore NOOP = new HypothesisRecordStore() {
        @Override
        public void save(HypothesisRecord record) {
        }

        @Override
        public void markOutcome(String id, String outcome, BigDecimal outcomePnl,
                                BigDecimal exitPrice, Instant closedAt) {
        }

        @Override
        public List<HypothesisRecord> recent(int limit) {
            return List.of();
        }
    };

    /** Postgres-backed store. */
    final class Jdbc implements HypothesisRecordStore {

        private final JdbcTemplate jdbc;

        public Jdbc(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public void save(HypothesisRecord r) {
            // Idempotent on id (the order id): a redelivered/duplicate execution leaves one row.
            jdbc.update("""
                    insert into hypothesis_record
                      (id, created_at, instrument, direction, horizon, conviction, thesis, book,
                       quantity, backtest_supported, order_id, order_status,
                       entry_price, horizon_expires_at, feed_mode)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (id) do nothing
                    """,
                    r.id(), ts(r.timestampMillis()), r.instrumentId(), r.direction(), r.horizon(),
                    r.conviction(), r.thesis(), r.book(), r.quantity(), r.backtestSupported(),
                    r.orderId(), r.orderStatus(), r.entryPrice(), ts(r.expiresAtMillis()),
                    io.jethro.messaging.Provenance.mode().name()); // ADR-0029: tag the thesis's feed mode
        }

        @Override
        public void markOutcome(String id, String outcome, BigDecimal outcomePnl,
                                BigDecimal exitPrice, Instant closedAt) {
            // outcome IS NULL guard = scored exactly once, even if the sweep re-runs.
            jdbc.update("""
                    update hypothesis_record
                    set outcome = ?, outcome_pnl = ?, exit_price = ?, closed_at = ?
                    where id = ? and outcome is null
                    """,
                    outcome, outcomePnl, exitPrice,
                    OffsetDateTime.ofInstant(closedAt, ZoneOffset.UTC), id);
        }

        @Override
        public List<HypothesisRecord> recent(int limit) {
            return jdbc.query("""
                    select id, created_at, instrument, direction, horizon, conviction, thesis, book,
                           quantity, backtest_supported, order_id, order_status,
                           entry_price, horizon_expires_at, outcome, outcome_pnl, exit_price
                    from hypothesis_record where feed_mode = ? order by created_at desc limit ?
                    """,
                    (rs, i) -> new HypothesisRecord(
                            rs.getString("id"),
                            rs.getObject("created_at", OffsetDateTime.class).toInstant().toEpochMilli(),
                            rs.getString("instrument"), rs.getString("direction"), rs.getString("horizon"),
                            rs.getString("conviction"), rs.getString("thesis"), rs.getString("book"),
                            rs.getBigDecimal("quantity"),
                            (Boolean) rs.getObject("backtest_supported"),
                            rs.getString("order_id"), rs.getString("order_status"),
                            rs.getBigDecimal("entry_price"),
                            rs.getObject("horizon_expires_at", OffsetDateTime.class) == null ? 0
                                    : rs.getObject("horizon_expires_at", OffsetDateTime.class).toInstant().toEpochMilli(),
                            rs.getString("outcome"),
                            rs.getBigDecimal("outcome_pnl"),
                            rs.getBigDecimal("exit_price")),
                    io.jethro.messaging.Provenance.mode().name(), limit);
        }

        private static OffsetDateTime ts(long epochMillis) {
            return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC);
        }
    }
}
