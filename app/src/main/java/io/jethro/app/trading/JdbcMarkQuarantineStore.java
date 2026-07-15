package io.jethro.app.trading;

import io.jethro.trading.runtime.MarkCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * Durable {@link MarkCache.QuarantineStore} over the {@code mark_quarantine} table (V26):
 * an operator's corporate-action / bad-print freeze survives a process restart (ADR-0024,
 * review GAP-2). Writes are idempotent upserts and run only on the rare trip/clear paths,
 * never per tick. A store failure never breaks the trading loop — it logs and the in-memory
 * quarantine still holds for this process (degrading to the pre-existing memory-only
 * behaviour), because a broken DB must not stop the market path.
 */
public final class JdbcMarkQuarantineStore implements MarkCache.QuarantineStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcMarkQuarantineStore.class);

    private final JdbcTemplate jdbc;

    public JdbcMarkQuarantineStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void onQuarantine(String instrumentId, long lastGoodScaled, long suspectScaled) {
        try {
            jdbc.update("""
                    insert into mark_quarantine (instrument_id, last_good_scaled, suspect_scaled)
                    values (?, ?, ?)
                    on conflict (instrument_id) do update
                        set suspect_scaled = excluded.suspect_scaled, quarantined_at = now()
                    """, instrumentId, lastGoodScaled, suspectScaled);
        } catch (Exception e) {
            log.warn("could not persist quarantine for {} (in-memory freeze still holds this run): {}",
                    instrumentId, e.toString());
        }
    }

    @Override
    public void onClear(String instrumentId) {
        try {
            jdbc.update("delete from mark_quarantine where instrument_id = ?", instrumentId);
        } catch (Exception e) {
            log.warn("could not delete persisted quarantine for {} (cleared in memory): {}",
                    instrumentId, e.toString());
        }
    }

    @Override
    public List<Persisted> load() {
        try {
            return jdbc.query("""
                    select instrument_id, last_good_scaled, suspect_scaled from mark_quarantine
                    """, (rs, i) -> new Persisted(
                    rs.getString("instrument_id"),
                    rs.getLong("last_good_scaled"),
                    rs.getLong("suspect_scaled")));
        } catch (Exception e) {
            log.error("could not load persisted quarantines at boot ({}); quarantines from a prior "
                    + "run are NOT restored — re-freeze manually if a corporate action is pending", e.toString());
            return List.of();
        }
    }
}
