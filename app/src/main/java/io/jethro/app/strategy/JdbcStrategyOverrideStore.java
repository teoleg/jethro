package io.jethro.app.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Postgres-backed {@link StrategyOverrideStore} over {@code strategy_param_override} +
 * {@code strategy_param_change} (V33, ADR-0052). Writes are idempotent upserts on the rare tuning
 * path, never per cycle. A store failure logs and leaves the in-memory override in force this run —
 * a broken DB must never stop the strategy from trading.
 */
public final class JdbcStrategyOverrideStore implements StrategyOverrideStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcStrategyOverrideStore.class);

    private final JdbcTemplate jdbc;

    public JdbcStrategyOverrideStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, String> load() {
        try {
            Map<String, String> out = new LinkedHashMap<>();
            jdbc.query("select param, value from strategy_param_override",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                            out.put(rs.getString("param"), rs.getString("value")));
            return out;
        } catch (Exception e) {
            log.error("could not load strategy overrides at boot ({}); running on config defaults", e.toString());
            return Map.of();
        }
    }

    @Override
    public void save(String param, String value, String actor, String note, String oldValue) {
        try {
            jdbc.update("""
                    insert into strategy_param_override (param, value, updated_by, note)
                    values (?, ?, ?, ?)
                    on conflict (param) do update
                        set value = excluded.value, updated_by = excluded.updated_by,
                            note = excluded.note, updated_at = now()
                    """, param, value, actor, note);
            audit(param, oldValue, value, actor, note);
        } catch (Exception e) {
            log.warn("could not persist strategy override {}={} (in-memory value still applies): {}",
                    param, value, e.toString());
        }
    }

    @Override
    public void delete(String param, String actor, String oldValue) {
        try {
            jdbc.update("delete from strategy_param_override where param = ?", param);
            audit(param, oldValue, null, actor, "reset to config default");
        } catch (Exception e) {
            log.warn("could not delete strategy override {} (reset in memory): {}", param, e.toString());
        }
    }

    private void audit(String param, String oldValue, String newValue, String actor, String note) {
        try {
            jdbc.update("""
                    insert into strategy_param_change (param, old_value, new_value, actor, note)
                    values (?, ?, ?, ?, ?)
                    """, param, oldValue, newValue, actor, note);
        } catch (Exception e) {
            log.warn("could not append strategy-param audit row for {}: {}", param, e.toString());
        }
    }

    @Override
    public List<Change> recentChanges(int limit) {
        try {
            return jdbc.query("""
                    select param, old_value, new_value, actor, note,
                           extract(epoch from changed_at) * 1000 as at_millis
                    from strategy_param_change order by changed_at desc limit ?
                    """, (rs, i) -> new Change(
                    rs.getString("param"), rs.getString("old_value"), rs.getString("new_value"),
                    rs.getString("actor"), rs.getString("note"), rs.getLong("at_millis")), limit);
        } catch (Exception e) {
            log.warn("could not load strategy-param audit history: {}", e.toString());
            return List.of();
        }
    }
}
