package io.jethro.app.risk;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Live status of the daily-return history the hedger's covariance is built from (ADR-0038): how
 * many days are on file, the latest day, how many instruments, whether that's enough to size a
 * hedge (≥ the covariance minimum), and — for the UI — whether/when it was seeded at boot. The
 * counts are read straight from {@code daily_close} so they're always the truth, not a cached
 * guess; the seed metadata is set once by {@link io.jethro.app.trading.SimHistorySeeder}.
 */
public final class HistoryStatus {

    /** Enough covered days for CovMath to build a covariance (mirrors VarService.MIN_OBSERVATIONS). */
    private static final int READY_MIN_DAYS = 20;

    private final JdbcTemplate jdbc;
    private volatile long seededAtMillis; // 0 until a boot seed runs
    private volatile String source = "live"; // live | sim-seed | existing

    public HistoryStatus(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void markSeeded(String source) {
        this.source = source;
        this.seededAtMillis = System.currentTimeMillis();
    }

    /** Called when a boot found enough existing history and skipped seeding. */
    public void markExisting() {
        this.source = "existing";
        this.seededAtMillis = System.currentTimeMillis();
    }

    public record Snapshot(long days, String latestDay, long instruments, boolean ready,
                           String source, long seededAtMillis) {
    }

    public Snapshot snapshot() {
        try {
            Long days = jdbc.queryForObject("select count(distinct day) from daily_close", Long.class);
            Long instruments = jdbc.queryForObject("select count(distinct instrument) from daily_close", Long.class);
            String latest = jdbc.query("select max(day) as d from daily_close",
                    rs -> rs.next() && rs.getDate("d") != null ? rs.getDate("d").toString() : null);
            long d = days != null ? days : 0;
            return new Snapshot(d, latest, instruments != null ? instruments : 0,
                    d >= READY_MIN_DAYS, source, seededAtMillis);
        } catch (Exception e) {
            return new Snapshot(0, null, 0, false, source, seededAtMillis);
        }
    }
}
