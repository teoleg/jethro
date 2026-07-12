package io.jethro.app.chat;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Persists every chat turn for audit (ADR-0021). {@link #NOOP} is used when persistence is
 * off; {@link Jdbc} writes to the {@code chat_audit} table.
 */
public interface ChatAuditStore {

    void record(ChatResponse response, long latencyMillis);

    /** No-op audit (persistence disabled). */
    ChatAuditStore NOOP = (response, latencyMillis) -> {
    };

    /** Postgres-backed audit. */
    final class Jdbc implements ChatAuditStore {

        private final JdbcTemplate jdbc;

        public Jdbc(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public void record(ChatResponse r, long latencyMillis) {
            jdbc.update("""
                    insert into chat_audit
                      (id, asked_at, question, intent, book, instrument, answer, model, latency_millis)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID().toString(), OffsetDateTime.now(ZoneOffset.UTC),
                    r.question(), r.intent(), r.book(), r.instrument(), r.answer(), r.model(), latencyMillis);
        }
    }
}
