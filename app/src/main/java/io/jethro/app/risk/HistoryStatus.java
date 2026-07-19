package io.jethro.app.risk;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Live status of the daily-return history the hedger's covariance is built from (ADR-0038): how
 * many days are on file, the latest day, how many instruments, whether that's enough to size a
 * hedge (≥ the covariance minimum), and — for the UI — whether it seeded, is still trying, or
 * FAILED (with why). The counts are read straight from {@code daily_close} so they're always the
 * truth; the seed state is set by {@link io.jethro.app.trading.YahooHistorySeeder}.
 */
public final class HistoryStatus {

    /** Enough covered days for CovMath to build a covariance (mirrors VarService.MIN_OBSERVATIONS). */
    private static final int READY_MIN_DAYS = 20;

    private final JdbcTemplate jdbc;
    private volatile long seededAtMillis; // 0 until a boot seed runs
    private volatile String source = "seeding";      // seeding | yahoo-history | existing | failed
    private volatile String note = "history seed in progress…";

    public HistoryStatus(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void markSeeded(String source, String note) {
        this.source = source;
        this.note = note;
        this.seededAtMillis = System.currentTimeMillis();
    }

    /** Called when a boot found enough existing history and skipped seeding. */
    public void markExisting() {
        this.source = "existing";
        this.note = "loaded from stored daily history";
        this.seededAtMillis = System.currentTimeMillis();
    }

    /** Called when the seed could not fetch any usable history — an explicit error state. */
    public void markFailed(String reason) {
        this.source = "failed";
        this.note = reason;
        this.seededAtMillis = System.currentTimeMillis();
    }

    public record Snapshot(long days, String latestDay, long instruments, boolean ready,
                           String source, String note, long seededAtMillis) {
    }

    public Snapshot snapshot() {
        try {
            Long days = jdbc.queryForObject("select count(distinct day) from daily_close", Long.class);
            Long instruments = jdbc.queryForObject("select count(distinct instrument) from daily_close", Long.class);
            String latest = jdbc.query("select max(day) as d from daily_close",
                    rs -> rs.next() && rs.getDate("d") != null ? rs.getDate("d").toString() : null);
            long d = days != null ? days : 0;
            return new Snapshot(d, latest, instruments != null ? instruments : 0,
                    d >= READY_MIN_DAYS, source, note, seededAtMillis);
        } catch (Exception e) {
            // Don't guess "persistence off": surface the real reason (e.g. the daily_close table
            // isn't there yet) so the UI badge shows a true error instead of a vague "warming".
            return new Snapshot(0, null, 0, false, "unavailable",
                    "history unavailable: " + e.getMessage(), seededAtMillis);
        }
    }
}
