package io.jethro.uigateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Durable LMDB chart history: ordered range reads, retention pruning, idempotent re-put
 * (invariant 8), survival across a reopen, and read-path downsampling. Prices stay exact
 * decimal strings end to end (invariant 1).
 */
class LmdbMarkHistoryTest {

    private static final long HOUR = 3_600_000L;

    @Test
    void sinceReturnsPointsInChronologicalOrder(@TempDir Path dir) {
        try (var h = LmdbMarkHistory.open(dir, 16 * 1024 * 1024, 12 * HOUR)) {
            // deliberately out of order — the store must order by time, not insertion
            h.record("ES", "100.5", 3_000);
            h.record("ES", "100.1", 1_000);
            h.record("ES", "100.3", 2_000);

            List<MarkHistory.Point> all = h.since("ES", 0);
            assertEquals(List.of(
                    new MarkHistory.Point(1_000, "100.1"),
                    new MarkHistory.Point(2_000, "100.3"),
                    new MarkHistory.Point(3_000, "100.5")), all);

            // lower bound is inclusive
            assertEquals(List.of(
                    new MarkHistory.Point(2_000, "100.3"),
                    new MarkHistory.Point(3_000, "100.5")), h.since("ES", 2_000));
        }
    }

    @Test
    void instrumentsAreIsolated(@TempDir Path dir) {
        try (var h = LmdbMarkHistory.open(dir, 16 * 1024 * 1024, 12 * HOUR)) {
            h.record("ES", "100", 1_000);
            h.record("NQ", "200", 1_000);
            h.record("ES", "101", 2_000);

            assertEquals(List.of(
                    new MarkHistory.Point(1_000, "100"),
                    new MarkHistory.Point(2_000, "101")), h.since("ES", 0));
            assertEquals(List.of(new MarkHistory.Point(1_000, "200")), h.since("NQ", 0));
            assertEquals(2, h.instrumentCount());
            assertEquals(2, h.pointCount("ES"));
            assertEquals(1, h.pointCount("NQ"));
        }
    }

    @Test
    void rePuttingTheSameMarkIsIdempotent(@TempDir Path dir) {
        // Broker re-delivery / boot replay hits the same (instrument, timestamp) key — must not grow.
        try (var h = LmdbMarkHistory.open(dir, 16 * 1024 * 1024, 12 * HOUR)) {
            h.record("ES", "100.1", 1_000);
            h.record("ES", "100.1", 1_000);
            h.record("ES", "100.9", 1_000); // same key, later value wins
            assertEquals(1, h.pointCount("ES"));
            assertEquals(List.of(new MarkHistory.Point(1_000, "100.9")), h.since("ES", 0));
        }
    }

    @Test
    void expiredHeadIsPrunedOnWrite(@TempDir Path dir) {
        try (var h = LmdbMarkHistory.open(dir, 16 * 1024 * 1024, HOUR)) { // 1h retention
            long t0 = 10 * HOUR;
            h.record("ES", "100", t0);
            h.record("ES", "101", t0 + 30 * 60_000); // +30m, still live
            // a mark 2h later must evict everything older than 1h before it
            h.record("ES", "102", t0 + 2 * HOUR);
            List<MarkHistory.Point> pts = h.since("ES", 0);
            assertEquals(List.of(new MarkHistory.Point(t0 + 2 * HOUR, "102")), pts);
            assertEquals(1, h.pointCount("ES"));
        }
    }

    @Test
    void historySurvivesReopen(@TempDir Path dir) {
        try (var h = LmdbMarkHistory.open(dir, 16 * 1024 * 1024, 12 * HOUR)) {
            h.record("ES", "100", 1_000);
            h.record("ES", "101", 2_000);
        }
        try (var h = LmdbMarkHistory.open(dir, 16 * 1024 * 1024, 12 * HOUR)) {
            assertEquals(List.of(
                    new MarkHistory.Point(1_000, "100"),
                    new MarkHistory.Point(2_000, "101")), h.since("ES", 0));
        }
    }

    @Test
    void longWindowIsDownsampledButKeepsTheLatestPoint(@TempDir Path dir) {
        try (var h = LmdbMarkHistory.open(dir, 64 * 1024 * 1024, 12 * HOUR)) {
            int n = 20_000;
            for (int i = 0; i < n; i++) {
                h.record("ES", Integer.toString(i), 1_000L + i * 1_000L);
            }
            List<MarkHistory.Point> pts = h.since("ES", 0);
            assertTrue(pts.size() <= 2_001, "downsampled to the cap, got " + pts.size());
            assertTrue(pts.size() > 1_000, "should retain a dense-enough series, got " + pts.size());
            // first and last are exact — the chart's newest value must never be an interpolation
            assertEquals("0", pts.get(0).price());
            assertEquals(Integer.toString(n - 1), pts.get(pts.size() - 1).price());
            assertEquals(1_000L + (n - 1) * 1_000L, pts.get(pts.size() - 1).t());
        }
    }

    @Test
    void unknownInstrumentIsEmpty(@TempDir Path dir) {
        try (var h = LmdbMarkHistory.open(dir, 16 * 1024 * 1024, 12 * HOUR)) {
            assertEquals(List.of(), h.since("NOPE", 0));
            assertEquals(0, h.pointCount("NOPE"));
        }
    }
}
