package io.jethro.app.signal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Persistence for ADR-0055 phase-1 signal telemetry. Records a source's directional call, resolves it
 * by the realised forward return at horizon, and serves rolling per-source stats. feed_mode-scoped
 * (ADR-0029): every read filters to the running {@link io.jethro.messaging.Provenance#mode()} so a
 * sim→live switch never mixes a mode's signal quality into another's. Best-effort — a failed write is
 * logged and dropped (telemetry never sits on any decision path).
 */
public final class SignalTelemetryStore {

    private static final Logger log = LoggerFactory.getLogger(SignalTelemetryStore.class);

    private final JdbcTemplate jdbc;

    public SignalTelemetryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** An unresolved observation awaiting its forward return. */
    public record Open(String id, String source, String instrument, int direction, BigDecimal entryMark) {
    }

    /** True when this source already has an unresolved call open on this instrument (this mode). */
    public boolean hasOpen(String source, String instrument) {
        try {
            Long n = jdbc.queryForObject("""
                    select count(*) from signal_observations
                    where source = ? and instrument = ? and resolved = false and feed_mode = ?
                    """, Long.class, source, instrument, mode());
            return n != null && n > 0;
        } catch (Exception e) {
            return true; // on doubt, don't record a possible duplicate
        }
    }

    public void record(String id, String source, String instrument, int direction, BigDecimal entryMark,
                       Instant entryAt, int horizonSeconds) {
        try {
            jdbc.update("""
                    insert into signal_observations
                        (id, source, instrument, direction, entry_mark, entry_at, horizon_seconds, feed_mode)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (id) do nothing
                    """, id, source, instrument, direction, entryMark, Timestamp.from(entryAt),
                    horizonSeconds, mode());
        } catch (Exception e) {
            log.debug("signal telemetry record failed ({}/{}) — dropped: {}", source, instrument, e.toString());
        }
    }

    /** Open observations whose horizon has elapsed by {@code now} (this mode). */
    public List<Open> due(Instant now) {
        try {
            return jdbc.query("""
                    select id, source, instrument, direction, entry_mark from signal_observations
                    where resolved = false and feed_mode = ?
                      and entry_at + (horizon_seconds * interval '1 second') <= ?
                    order by entry_at limit 500
                    """, (rs, i) -> new Open(rs.getString("id"), rs.getString("source"),
                    rs.getString("instrument"), rs.getInt("direction"), rs.getBigDecimal("entry_mark")),
                    mode(), Timestamp.from(now));
        } catch (Exception e) {
            log.debug("signal telemetry due() failed: {}", e.toString());
            return List.of();
        }
    }

    public void resolve(String id, BigDecimal exitMark, double realizedReturn, String outcome, Instant at) {
        try {
            jdbc.update("""
                    update signal_observations
                    set resolved = true, exit_mark = ?, realized_return = ?, outcome = ?, resolved_at = ?
                    where id = ? and resolved = false
                    """, exitMark, BigDecimal.valueOf(realizedReturn), outcome, Timestamp.from(at), id);
        } catch (Exception e) {
            log.debug("signal telemetry resolve({}) failed: {}", id, e.toString());
        }
    }

    /** Resolved directional returns for one source since {@code since} (this mode), newest first. */
    public List<Double> resolvedReturns(String source, Instant since, int limit) {
        try {
            return jdbc.query("""
                    select realized_return from signal_observations
                    where source = ? and resolved = true and feed_mode = ? and resolved_at >= ?
                    order by resolved_at desc limit ?
                    """, (rs, i) -> rs.getBigDecimal("realized_return").doubleValue(),
                    source, mode(), Timestamp.from(since), limit);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Distinct sources seen (this mode) — resolved or open — so the view lists every live source. */
    public List<String> sources() {
        try {
            return jdbc.query("select distinct source from signal_observations where feed_mode = ? order by source",
                    (rs, i) -> rs.getString(1), mode());
        } catch (Exception e) {
            return List.of();
        }
    }

    public long openCount(String source) {
        try {
            Long n = jdbc.queryForObject("""
                    select count(*) from signal_observations
                    where source = ? and resolved = false and feed_mode = ?
                    """, Long.class, source, mode());
            return n == null ? 0 : n;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String mode() {
        return io.jethro.messaging.Provenance.mode().name();
    }
}
