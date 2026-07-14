package io.jethro.app.session;

import io.jethro.trading.riskpnl.ConsolidatedRisk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * The end-of-day boundary (ADR-0027): watches the {@link TradingCalendar} and, when the
 * session day rolls, freezes the ended day — closing marks into {@code daily_close}, firm
 * total P&L into {@code firm_equity}, per-book cumulative P&L into {@code book_equity}
 * (day attribution = consecutive-row differences, computed at read time, never stored
 * twice) — and expires working DAY orders. "Today's P&L" is then a real number that
 * survives restarts: live firm total minus the previous session's persisted close.
 *
 * <p>The close is the marks observed at the boundary check (≤ {@value #CHECK_SECONDS}s
 * after the calendar rolled) — honest for a continuous sim; a real exchange closing-auction
 * print is a live-feed refinement. Without persistence the day boundary still functions
 * (DAY expiry, in-memory today anchor); only history is skipped. A failed pass logs and
 * retries — the boundary is re-detected next check, so a transient DB error never loses
 * the rollover.
 */
public final class EodService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EodService.class);
    static final long CHECK_SECONDS = 1;

    /** Latest marks for the close snapshot (instrumentId → price), decoupled from trading-core. */
    public interface MarkSource {
        List<Mark> marks();

        record Mark(String instrumentId, BigDecimal price) {
        }
    }

    private final TradingCalendar calendar;
    private final JdbcTemplate jdbc;                 // null → no persistence: skip history writes
    private final Supplier<ConsolidatedRisk> risk;   // never null; may return null early in startup
    private final MarkSource marks;
    private final IntSupplier dayOrderExpirer;       // null → order module off

    private volatile LocalDate currentDay;
    private volatile BigDecimal previousCloseTotal;
    private volatile ScheduledExecutorService scheduler;

    public EodService(TradingCalendar calendar, JdbcTemplate jdbc, Supplier<ConsolidatedRisk> risk,
                      MarkSource marks, IntSupplier dayOrderExpirer) {
        this.calendar = calendar;
        this.jdbc = jdbc;
        this.risk = risk;
        this.marks = marks;
        this.dayOrderExpirer = dayOrderExpirer;
        this.currentDay = calendar.sessionDay();
        this.previousCloseTotal = seedPreviousClose(this.currentDay);
    }

    /** Restart mid-session: the anchor is the last persisted close BEFORE the current day. */
    private BigDecimal seedPreviousClose(LocalDate day) {
        if (jdbc == null) {
            return BigDecimal.ZERO;
        }
        try {
            List<BigDecimal> rows = jdbc.query(
                    "select total_pnl from firm_equity where day < ? order by day desc limit 1",
                    (rs, i) -> rs.getBigDecimal(1), day);
            return rows.isEmpty() ? BigDecimal.ZERO : rows.get(0);
        } catch (Exception e) {
            log.warn("could not seed previous close (starting from 0): {}", e.toString());
            return BigDecimal.ZERO;
        }
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "eod-boundary");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::checkOnce, CHECK_SECONDS, CHECK_SECONDS, TimeUnit.SECONDS);
        log.info("EOD boundary watcher started: calendar = {}, session day {}",
                calendar.description(), currentDay);
    }

    void checkOnce() {
        try {
            LocalDate day = calendar.sessionDay();
            LocalDate ended = currentDay;
            if (!day.isAfter(ended)) {
                return;
            }
            rollover(ended, day);
        } catch (Exception e) {
            log.warn("EOD pass failed (boundary re-detected next check): {}", e.toString());
        }
    }

    private void rollover(LocalDate ended, LocalDate newDay) {
        ConsolidatedRisk snapshot = risk.get();
        BigDecimal closeTotal = snapshot != null ? snapshot.total().totalPnl() : previousCloseTotal;
        if (jdbc != null) {
            for (MarkSource.Mark mark : marks.marks()) {
                jdbc.update("""
                        insert into daily_close (day, instrument, close) values (?, ?, ?)
                        on conflict (day, instrument) do update set close = excluded.close
                        """, ended, mark.instrumentId(), mark.price());
            }
            jdbc.update("""
                    insert into firm_equity (day, total_pnl) values (?, ?)
                    on conflict (day) do update set total_pnl = excluded.total_pnl
                    """, ended, closeTotal);
            if (snapshot != null) {
                for (ConsolidatedRisk.Group book : snapshot.byBook()) {
                    jdbc.update("""
                            insert into book_equity (day, book, realized_pnl, unrealized_pnl, total_pnl)
                            values (?, ?, ?, ?, ?)
                            on conflict (day, book) do update set realized_pnl = excluded.realized_pnl,
                                unrealized_pnl = excluded.unrealized_pnl, total_pnl = excluded.total_pnl
                            """, ended, book.key(), book.realizedPnl(), book.unrealizedPnl(), book.totalPnl());
                }
            }
        }
        int expired = dayOrderExpirer != null ? dayOrderExpirer.getAsInt() : 0;
        // Anchor first, then advance the day — a crash in between re-runs the rollover (upserts
        // make that idempotent) rather than losing it.
        previousCloseTotal = closeTotal;
        currentDay = newDay;
        log.info("EOD: session {} closed at firm P&L {} ({} DAY orders expired); session {} open",
                ended, closeTotal.toPlainString(), expired, newDay);
    }

    /** The current session day (for the API and consumers that need "today"). */
    public LocalDate sessionDay() {
        return currentDay;
    }

    /** Previous session's persisted firm close — the anchor "today's P&L" measures from. */
    public BigDecimal previousCloseTotal() {
        return previousCloseTotal;
    }

    /** Live today-so-far P&L: current firm total minus the previous session close. */
    public BigDecimal todayPnl() {
        ConsolidatedRisk snapshot = risk.get();
        BigDecimal total = snapshot != null ? snapshot.total().totalPnl() : previousCloseTotal;
        return total.subtract(previousCloseTotal);
    }

    public String calendarDescription() {
        return calendar.description();
    }

    /** Recent daily firm history (day, close total, day-over-day P&L), oldest first. */
    public List<DailyRow> recentDays(int limit) {
        if (jdbc == null) {
            return List.of();
        }
        List<Object[]> rows = jdbc.query(
                "select day, total_pnl from firm_equity order by day desc limit ?",
                (rs, i) -> new Object[]{rs.getObject("day", LocalDate.class), rs.getBigDecimal("total_pnl")},
                limit + 1); // one extra so the oldest shown row still has a day-P&L
        List<DailyRow> out = new java.util.ArrayList<>();
        for (int i = rows.size() - 1; i >= 0; i--) { // oldest first
            BigDecimal total = (BigDecimal) rows.get(i)[1];
            BigDecimal prev = i + 1 < rows.size() ? (BigDecimal) rows.get(i + 1)[1] : null;
            out.add(new DailyRow((LocalDate) rows.get(i)[0], total,
                    prev != null ? total.subtract(prev) : null));
        }
        return out.size() > limit ? out.subList(out.size() - limit, out.size()) : out;
    }

    /** One persisted session: close total and the day's P&L (null when no prior day exists). */
    public record DailyRow(LocalDate day, BigDecimal totalPnl, BigDecimal dayPnl) {
    }

    @Override
    public void close() {
        var s = scheduler;
        if (s != null) {
            s.shutdownNow();
        }
    }
}
