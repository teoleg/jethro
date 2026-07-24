package io.jethro.app.training;

import io.jethro.app.trading.HistoryClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The ADR-0053 training-bars table (V38): real daily bars for the learned-advisory-signal training
 * set, kept separate from the runtime {@code daily_close} (which is sim-contaminated at the tail).
 * Idempotent past-dated upserts; a wipe is recovered by re-loading from Tiingo. Read for offline
 * model training only — never a number into live sizing/risk (ADR-0016 / invariant 7).
 */
public final class TrainingBarsStore {

    private static final Logger log = LoggerFactory.getLogger(TrainingBarsStore.class);

    private final JdbcTemplate jdbc;

    public TrainingBarsStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Empty (or unreadable) → the loader (re)builds it from Tiingo. */
    public boolean isEmpty() {
        try {
            Long n = jdbc.queryForObject("select count(*) from training_bars", Long.class);
            return n == null || n == 0;
        } catch (Exception e) {
            log.warn("training_bars count failed ({}) — treating as empty", e.toString());
            return true;
        }
    }

    /** Idempotently inserts one instrument's cleaned daily series; returns rows written. */
    public int upsert(String instrument, HistoryClient.History h, String source) {
        long[] days = h.epochDays();
        double[] closes = h.closes();
        long[] vols = h.volumes();
        int written = 0;
        for (int i = 0; i < days.length; i++) {
            double close = closes[i];
            if (!(close > 0)) {
                continue;
            }
            LocalDate day = LocalDate.ofEpochDay(days[i]);
            jdbc.update("""
                    insert into training_bars (instrument, day, adj_close, volume, source)
                    values (?, ?, ?, ?, ?)
                    on conflict (instrument, day) do nothing
                    """, instrument, day, BigDecimal.valueOf(close), vols[i], source);
            written++;
        }
        return written;
    }

    /** One bar for feature building: epoch-day, adjusted close (as double — analytics, not the money
     *  ledger; a return/vol indicator never feeds positions/PnL, ADR-0016), and volume. */
    public record Bar(long epochDay, double adjClose, long volume) {
    }

    /** Instruments present, sorted — the training universe actually on file. */
    public java.util.List<String> instruments() {
        try {
            return jdbc.query("select distinct instrument from training_bars order by instrument",
                    (rs, i) -> rs.getString(1));
        } catch (Exception e) {
            return java.util.List.of();
        }
    }

    /** One instrument's daily bars, ascending by day (point-in-time order for feature building). */
    public java.util.List<Bar> series(String instrument) {
        try {
            return jdbc.query("""
                    select day, adj_close, volume from training_bars
                    where instrument = ? order by day
                    """, (rs, i) -> new Bar(
                    rs.getObject("day", LocalDate.class).toEpochDay(),
                    rs.getBigDecimal("adj_close").doubleValue(),
                    rs.getLong("volume")), instrument);
        } catch (Exception e) {
            log.warn("training_bars series({}) failed: {}", instrument, e.toString());
            return java.util.List.of();
        }
    }

    public record Status(long rows, int instruments, String firstDay, String lastDay) {
    }

    public Status status() {
        try {
            return jdbc.queryForObject("""
                    select count(*) rows, count(distinct instrument) instruments,
                           min(day) first_day, max(day) last_day
                    from training_bars
                    """, (rs, i) -> new Status(
                    rs.getLong("rows"), rs.getInt("instruments"),
                    rs.getObject("first_day") == null ? null : rs.getObject("first_day", LocalDate.class).toString(),
                    rs.getObject("last_day") == null ? null : rs.getObject("last_day", LocalDate.class).toString()));
        } catch (Exception e) {
            return new Status(0, 0, null, null);
        }
    }
}
