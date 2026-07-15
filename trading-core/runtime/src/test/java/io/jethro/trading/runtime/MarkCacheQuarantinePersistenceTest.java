package io.jethro.trading.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Restart-survival for the corporate-action quarantine (review GAP-2): a freeze must
 * persist through the {@link MarkCache.QuarantineStore} and re-arm on a fresh cache, so a
 * process restart cannot silently disarm an operator's freeze. Uses an in-memory store
 * standing in for the JDBC one.
 */
class MarkCacheQuarantinePersistenceTest {

    /** In-memory QuarantineStore mirroring the JDBC upsert/delete/load semantics. */
    private static final class InMemoryStore implements MarkCache.QuarantineStore {
        final Map<String, Persisted> rows = new LinkedHashMap<>();

        @Override
        public void onQuarantine(String id, long lastGood, long suspect) {
            rows.put(id, new Persisted(id, lastGood, suspect));
        }

        @Override
        public void onClear(String id) {
            rows.remove(id);
        }

        @Override
        public List<Persisted> load() {
            return new ArrayList<>(rows.values());
        }
    }

    private static final MarkCache.JumpThresholds GUARD = id -> 2000; // 20%

    @Test
    void aTrippedQuarantineIsPersistedAndReArmsOnAFreshCache() {
        var store = new InMemoryStore();

        // Process 1: establish a good mark, then a +100% jump trips (and persists) the freeze.
        var cache1 = new MarkCache(GUARD, store);
        cache1.update("AAPL", 190_000_000L, 1, 1, "sim");
        cache1.update("AAPL", 400_000_000L, 2, 2, "sim"); // trips
        assertTrue(cache1.get("AAPL").quarantined());
        assertEquals(1, store.rows.size(), "the freeze was persisted");

        // Process 2 (restart): a brand-new cache restores from the store and is still frozen.
        var cache2 = new MarkCache(GUARD, store);
        assertTrue(cache2.get("AAPL") == null, "nothing in memory before restore");
        cache2.restoreQuarantines();
        assertTrue(cache2.get("AAPL").quarantined(), "quarantine survived the restart");

        // A post-restart mark is still REJECTED (the restored freeze is not stale-exempt).
        cache2.update("AAPL", 410_000_000L, 3, 3, "sim");
        assertTrue(cache2.get("AAPL").quarantined(), "still frozen");
        assertEquals(190_000_000L, cache2.get("AAPL").priceScaled(), "still shows the last good mark, not the suspect");
        assertEquals(new java.math.BigDecimal("190.000000"),
                cache2.quarantined().get(0).lastGoodPrice(), "last-good restored exactly");
    }

    @Test
    void clearingRemovesTheDurableRowSoItDoesNotComeBackOnRestart() {
        var store = new InMemoryStore();
        var cache1 = new MarkCache(GUARD, store);
        cache1.update("AAPL", 190_000_000L, 1, 1, "sim");
        cache1.update("AAPL", 400_000_000L, 2, 2, "sim");
        assertTrue(cache1.clearQuarantine("AAPL"));
        assertTrue(store.rows.isEmpty(), "clear removed the durable freeze");

        // Restart: nothing to restore.
        var cache2 = new MarkCache(GUARD, store);
        cache2.restoreQuarantines();
        assertTrue(cache2.get("AAPL") == null || !cache2.get("AAPL").quarantined(),
                "a cleared instrument does not re-freeze on restart");
    }

    @Test
    void restoredQuarantineTakesPrecedenceOverAWarmStaleLoad() {
        var store = new InMemoryStore();
        store.onQuarantine("AAPL", 190_000_000L, 400_000_000L);

        var cache = new MarkCache(GUARD, store);
        // Warm-load order: LMDB stale mark first, then restore (as TradingCoreRuntime.start does).
        cache.loadStale("AAPL", 195_000_000L, 1, "sim");
        cache.restoreQuarantines();

        assertTrue(cache.get("AAPL").quarantined(), "restore overrides the stale warm mark");
        assertFalse(cache.get("AAPL").stale(), "a restored freeze is a live freeze, not stale-exempt");
        assertEquals(190_000_000L, cache.get("AAPL").priceScaled(), "last-good, not the warm mark");
    }
}
