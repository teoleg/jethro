package io.jethro.uigateway;

import java.util.List;

/**
 * Rolling recent price history per instrument for the interactive "last N hours" chart
 * (ADR-0017 market view). Fed from the {@code md.marks} stream; bounded by a retention
 * window so the store stays flat. This is the <em>recent</em> series only — durable
 * long-term history is the S3 Parquet tick archive (ADR-0014), a separate concern.
 *
 * <p>Prices are kept as strings (invariant 1 — never a float). Marks are ~1Hz conflated,
 * so a 12h window is ~43,000 points/instrument.
 *
 * <p>Two implementations: {@link InMemoryMarkHistory} (a bounded ring, used in tests and
 * when no history path is configured) and {@link LmdbMarkHistory} (durable, memory-mapped,
 * ordered range scans — the production wiring, so a long window survives a restart and reads
 * fast without a full broker replay).
 */
public interface MarkHistory {

    /** One conflated mark: provider timestamp (ms) and the price as an exact decimal string. */
    record Point(long t, String price) {
    }

    /** The retention window in milliseconds — points older than this are evicted. */
    long retentionMillis();

    /** Appends a mark and evicts anything older than the retention window. */
    void record(String instrumentId, String price, long timestampMillis);

    /** Points at or after {@code sinceMillis}, oldest first. Empty if the instrument is unknown. */
    List<Point> since(String instrumentId, long sinceMillis);

    /** Number of instruments with recorded history (for diagnostics). */
    int instrumentCount();

    /** Current point count for one instrument (for diagnostics). */
    int pointCount(String instrumentId);
}
