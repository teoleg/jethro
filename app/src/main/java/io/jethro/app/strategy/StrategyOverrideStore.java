package io.jethro.app.strategy;

import java.util.List;
import java.util.Map;

/**
 * Durable store for live strategy-param overrides (ADR-0052). The effective value of a dial is the
 * override loaded here, else the {@code application.properties} default. Every set/reset also appends
 * an audit row — a live-set risk dial's provenance is that row (who/when/old→new), which is what the
 * "every risk number carries its source" convention requires.
 *
 * <p>A {@link #NONE} no-op backs the in-memory-only mode (persistence off): overrides then live only
 * in {@link StrategyControl} for the process lifetime. Store failures never break tuning — they log
 * and the in-memory value still applies (fail-safe: a broken DB must not wedge the strategy).
 */
public interface StrategyOverrideStore {

    /** One audited change (newest first in {@link #recentChanges}). */
    record Change(String param, String oldValue, String newValue, String actor, String note, long atMillis) {
    }

    /** Current overrides (param → value) at boot; empty if none / on error. */
    Map<String, String> load();

    /** Upsert an override and append an audit row. {@code oldValue} is the effective value before. */
    void save(String param, String value, String actor, String note, String oldValue);

    /** Delete an override (reset to config default) and append an audit row. */
    void delete(String param, String actor, String oldValue);

    /** Recent audited changes, newest first (for the UI history view). */
    List<Change> recentChanges(int limit);

    /** In-memory-only backing: nothing is persisted, nothing is audited durably. */
    StrategyOverrideStore NONE = new StrategyOverrideStore() {
        @Override public Map<String, String> load() {
            return Map.of();
        }

        @Override public void save(String param, String value, String actor, String note, String oldValue) {
        }

        @Override public void delete(String param, String actor, String oldValue) {
        }

        @Override public List<Change> recentChanges(int limit) {
            return List.of();
        }
    };
}
