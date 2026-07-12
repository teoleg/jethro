package io.jethro.trading.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LmdbStateStoreTest {

    private static final long TEN_MB = 10L * 1024 * 1024;

    @Test
    void dedupeDetectsRedelivery(@TempDir Path dir) {
        try (var store = LmdbStateStore.open(dir, TEN_MB)) {
            assertTrue(store.markSeenIfNew("evt-1", 1000L), "first delivery is new");
            assertFalse(store.markSeenIfNew("evt-1", 2000L), "redelivery must be detected");
            assertTrue(store.markSeenIfNew("evt-2", 3000L), "different event is new");
        }
    }

    @Test
    void dedupeSurvivesRestart(@TempDir Path dir) {
        try (var store = LmdbStateStore.open(dir, TEN_MB)) {
            assertTrue(store.markSeenIfNew("evt-1", 1000L));
        }
        // reopen: the whole point of LMDB over in-memory (ADR-0014)
        try (var store = LmdbStateStore.open(dir, TEN_MB)) {
            assertFalse(store.markSeenIfNew("evt-1", 2000L), "dedupe must survive restart");
        }
    }

    @Test
    void marksRoundTripAcrossRestart(@TempDir Path dir) {
        try (var store = LmdbStateStore.open(dir, TEN_MB)) {
            store.putMark("AAPL", 123_456_789L, 1_700_000_000_000L, "sim");
            store.putMark("MSFT", 987_654_321L, 1_700_000_000_001L, "sim");
            store.putMark("AAPL", 123_456_790L, 1_700_000_000_002L, "sim"); // overwrite
        }
        List<String> loaded = new ArrayList<>();
        try (var store = LmdbStateStore.open(dir, TEN_MB)) {
            store.forEachMark((id, price, ts, source) ->
                    loaded.add(id + ":" + price + ":" + ts + ":" + source));
        }
        loaded.sort(String::compareTo);
        assertEquals(List.of(
                "AAPL:123456790:1700000000002:sim",
                "MSFT:987654321:1700000000001:sim"), loaded);
    }
}
