package io.jethro.uigateway;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link MarkHistory}: a per-instrument ring bounded by the retention window.
 * Used in tests and as the fallback when no durable history path is configured. The
 * durable production path is {@link LmdbMarkHistory}.
 */
public final class InMemoryMarkHistory implements MarkHistory {

    private final long retentionMillis;
    private final Map<String, Deque<Point>> series = new ConcurrentHashMap<>();

    public InMemoryMarkHistory(long retentionMillis) {
        this.retentionMillis = retentionMillis;
    }

    @Override
    public long retentionMillis() {
        return retentionMillis;
    }

    @Override
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

    @Override
    public int instrumentCount() {
        return series.size();
    }

    @Override
    public int pointCount(String instrumentId) {
        Deque<Point> points = series.get(instrumentId);
        if (points == null) {
            return 0;
        }
        synchronized (points) {
            return points.size();
        }
    }

    @Override
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
