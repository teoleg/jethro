package io.jethro.app.hypothesis;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Persists executed AI hypotheses (ADR-0022). {@link #NOOP} is used when persistence is off —
 * the lifecycle still keeps them in-memory for the session, they just don't survive a restart;
 * {@link Jdbc} writes to and reads from {@code hypothesis_record}.
 */
public interface HypothesisRecordStore {

    void save(HypothesisRecord record);

    /** Most-recent executed hypotheses (newest first), up to {@code limit}. */
    List<HypothesisRecord> recent(int limit);

    /** No-op store (persistence disabled): nothing survives a restart. */
    HypothesisRecordStore NOOP = new HypothesisRecordStore() {
        @Override
        public void save(HypothesisRecord record) {
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
                       quantity, backtest_supported, order_id, order_status)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (id) do nothing
                    """,
                    r.id(), OffsetDateTime.ofInstant(Instant.ofEpochMilli(r.timestampMillis()), ZoneOffset.UTC),
                    r.instrumentId(), r.direction(), r.horizon(), r.conviction(), r.thesis(), r.book(),
                    r.quantity(), r.backtestSupported(), r.orderId(), r.orderStatus());
        }

        @Override
        public List<HypothesisRecord> recent(int limit) {
            return jdbc.query("""
                    select id, created_at, instrument, direction, horizon, conviction, thesis, book,
                           quantity, backtest_supported, order_id, order_status
                    from hypothesis_record order by created_at desc limit ?
                    """,
                    (rs, i) -> new HypothesisRecord(
                            rs.getString("id"),
                            rs.getObject("created_at", OffsetDateTime.class).toInstant().toEpochMilli(),
                            rs.getString("instrument"), rs.getString("direction"), rs.getString("horizon"),
                            rs.getString("conviction"), rs.getString("thesis"), rs.getString("book"),
                            rs.getBigDecimal("quantity"),
                            (Boolean) rs.getObject("backtest_supported"),
                            rs.getString("order_id"), rs.getString("order_status")),
                    limit);
        }
    }
}
