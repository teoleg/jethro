package io.jethro.uigateway;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rolling in-memory price history per instrument for the interactive "last N hours" chart
 * (ADR-0006/0017 market view). Fed from the {@code md.marks} stream; bounded by a
 * retention window so memory stays flat. This is the <em>recent</em> series only — durable
 * long-term history is the S3 Parquet tick archive (ADR-0014), a separate concern.
 *
 * <p>Prices are kept as strings (invariant 1 — never a float). ~1Hz conflated marks, so a
 * 2h window is ~7,200 points/instrument.
 */
public final class MarkHistory {

    public record Point(long t, String price) {
    }

    private final long retentionMillis;
    private final Map<String, Deque<Point>> series = new ConcurrentHashMap<>();

    public MarkHistory(long retentionMillis) {
        this.retentionMillis = retentionMillis;
    }

    /** Appends a mark and evicts anything older than the retention window. */
    public void record(String instrumentId, String price, long timestampMillis) {
        Deque<Point> points = series.computeIfAbsent(instrumentId, k -> new ArrayDeque<>());
        synchronized (points) {
            points.addLast(new Point(timestampMillis, price));
            long cutoff = timestampMillis - retentionMillis;
            while (!points.isEmpty() && points.peekFirst().t() < cutoff) {
                points.removeFirst();
            }
        }
    }

    /** Number of instruments with recorded history (for diagnostics). */
    public int instrumentCount() {
        return series.size();
    }

    /** Current point count for one instrument (for diagnostics). */
    public int pointCount(String instrumentId) {
        Deque<Point> points = series.get(instrumentId);
        if (points == null) {
            return 0;
        }
        synchronized (points) {
            return points.size();
        }
    }

    /** Points at or after {@code sinceMillis}, oldest first. Empty if the instrument is unknown. */
    public List<Point> since(String instrumentId, long sinceMillis) {
        Deque<Point> points = series.get(instrumentId);
        if (points == null) {
            return List.of();
        }
        synchronized (points) {
            List<Point> out = new ArrayList<>();
            for (Point p : points) {
                if (p.t() >= sinceMillis) {
                    out.add(p);
                }
            }
            return out;
        }
    }
}
