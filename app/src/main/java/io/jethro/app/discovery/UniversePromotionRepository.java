package io.jethro.app.discovery;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * Append-only audit log of the ADR-0060 promotion gate's decisions ({@code universe_promotion}, V45).
 * Phase 1 writes PROPOSED / REJECTED rows in dry-run; the controller reads them back for the UI. The
 * refdata write path (Phase 2) reuses this same log for PROMOTED / EVICTED rows.
 */
public final class UniversePromotionRepository implements PromotionAudit {

    /** One decision row. {@code action} ∈ PROPOSED/PROMOTED/EVICTED/REJECTED; {@code outcome} is the
     *  policy {@link UniversePromotionPolicy.Outcome} name. */
    public record Audit(long id, long atMillis, String sessionEpoch, String feedMode, String instrumentId,
                        String action, String outcome, Double score, Integer distinctDays, String sources,
                        String reason, boolean dryRun) {
    }

    private final JdbcTemplate jdbc;

    public UniversePromotionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Record one gate decision. Never throws into the caller's evaluation loop — audit is best-effort. */
    @Override
    public void record(long atMillis, String sessionEpoch, String feedMode, String instrumentId, String action,
                       String outcome, Double score, Integer distinctDays, String sources, String reason,
                       boolean dryRun) {
        jdbc.update(
                "insert into universe_promotion (at_millis, session_epoch, feed_mode, instrument_id, action, "
                        + "outcome, score, distinct_days, sources, reason, dry_run) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                atMillis, sessionEpoch, feedMode, instrumentId, action, outcome, score, distinctDays,
                sources, reason, dryRun);
    }

    /** Most-recent decisions first, for the review UI. */
    public List<Audit> recent(int limit) {
        return jdbc.query(
                "select id, at_millis, session_epoch, feed_mode, instrument_id, action, outcome, score, "
                        + "distinct_days, sources, reason, dry_run from universe_promotion "
                        + "order by at_millis desc, id desc limit ?",
                (rs, i) -> new Audit(
                        rs.getLong("id"),
                        rs.getLong("at_millis"),
                        rs.getString("session_epoch"),
                        rs.getString("feed_mode"),
                        rs.getString("instrument_id"),
                        rs.getString("action"),
                        rs.getString("outcome"),
                        // score is NUMERIC → JDBC returns BigDecimal; distinct_days is INTEGER. Read via
                        // Number so neither a BigDecimal nor a boxed int throws a ClassCastException.
                        rs.getObject("score") instanceof Number sc ? sc.doubleValue() : null,
                        rs.getObject("distinct_days") instanceof Number dd ? dd.intValue() : null,
                        rs.getString("sources"),
                        rs.getString("reason"),
                        rs.getBoolean("dry_run")),
                limit);
    }

    /** How many PROMOTED rows exist since {@code fromMillis} — the daily budget counter (Phase 2 uses
     *  actual promotions; Phase 1's dry-run budget is in-memory per cycle). */
    public int promotedCountSince(long fromMillis) {
        Integer n = jdbc.queryForObject(
                "select count(*) from universe_promotion where action = 'PROMOTED' and at_millis >= ?",
                Integer.class, fromMillis);
        return n == null ? 0 : n;
    }

    /** Whether a PROPOSED row for this name was already written since {@code sinceMillis} — dedupe so the
     *  daily dry-run (and reboots) don't append a duplicate proposal for the same name every cycle. */
    public boolean proposedSince(String instrumentId, long sinceMillis) {
        Integer n = jdbc.queryForObject(
                "select count(*) from universe_promotion where instrument_id = ? and action = 'PROPOSED' "
                        + "and at_millis >= ?",
                Integer.class, instrumentId, sinceMillis);
        return n != null && n > 0;
    }
}
