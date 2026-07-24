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
    private volatile BigDecimal openTotal;                    // firm total at session open (nullable)
    // Pre-boundary buffer: the last state seen BEFORE the calendar rolled. The sim applies its
    // close→open gap exactly on the boundary, so "the close" must be the state from the last
    // check before it (≤ CHECK_SECONDS old), not the post-gap state at rollover detection.
    private volatile ConsolidatedRisk bufferedRisk;
    private volatile List<MarkSource.Mark> bufferedMarks;
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
        this.openTotal = seedOpen(this.currentDay);
    }

    /** Restart mid-session: today's open (for the overnight/intraday split) if persisted. */
    private BigDecimal seedOpen(LocalDate day) {
        if (jdbc == null) {
            return null;
        }
        try {
            List<BigDecimal> rows = jdbc.query(
                    "select open_pnl from firm_equity where day = ? and feed_mode = ?",
                    (rs, i) -> rs.getBigDecimal(1), day, io.jethro.messaging.Provenance.mode().name());
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            return null; // split unavailable until the next boundary — disclosed via null
        }
    }

    /** Restart mid-session: the anchor is the last persisted close BEFORE the current day. */
    private BigDecimal seedPreviousClose(LocalDate day) {
        if (jdbc == null) {
            return BigDecimal.ZERO;
        }
        try {
            List<BigDecimal> rows = jdbc.query(
                    "select total_pnl from firm_equity where day < ? and feed_mode = ? order by day desc limit 1",
                    (rs, i) -> rs.getBigDecimal(1), day, io.jethro.messaging.Provenance.mode().name());
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
                // Intra-day: refresh the pre-boundary buffer so the NEXT rollover has a
                // guaranteed pre-gap close (≤ CHECK_SECONDS before the boundary).
                bufferedRisk = risk.get();
                bufferedMarks = marks.marks();
                return;
            }
            rollover(ended, day);
        } catch (Exception e) {
            log.warn("EOD pass failed (boundary re-detected next check): {}", e.toString());
        }
    }

    private void rollover(LocalDate ended, LocalDate newDay) {
        // Close = the buffered PRE-boundary state (the sim's overnight gap lands ON the
        // boundary; the state at detection time is already the new day's open). First-ever
        // check landing straight on a boundary has no buffer — fall back to current, disclosed.
        ConsolidatedRisk closeSnapshot = bufferedRisk != null ? bufferedRisk : risk.get();
        List<MarkSource.Mark> closeMarks = bufferedMarks != null ? bufferedMarks : marks.marks();
        ConsolidatedRisk snapshot = closeSnapshot;
        BigDecimal closeTotal = snapshot != null ? snapshot.total().comprehensivePnl() : previousCloseTotal;
        if (jdbc != null) {
            for (MarkSource.Mark mark : closeMarks) {
                jdbc.update("""
                        insert into daily_close (day, instrument, close) values (?, ?, ?)
                        on conflict (day, instrument) do update set close = excluded.close
                        """, ended, mark.instrumentId(), mark.price());
            }
            String mode = io.jethro.messaging.Provenance.mode().name();
            jdbc.update("""
                    insert into firm_equity (day, total_pnl, feed_mode) values (?, ?, ?)
                    on conflict (day, feed_mode) do update set total_pnl = excluded.total_pnl
                    """, ended, closeTotal, mode);
            if (snapshot != null) {
                for (ConsolidatedRisk.Group book : snapshot.byBook()) {
                    jdbc.update("""
                            insert into book_equity (day, book, realized_pnl, unrealized_pnl, total_pnl, feed_mode)
                            values (?, ?, ?, ?, ?, ?)
                            on conflict (day, feed_mode, book) do update set realized_pnl = excluded.realized_pnl,
                                unrealized_pnl = excluded.unrealized_pnl, total_pnl = excluded.total_pnl
                            """, ended, book.key(), book.realizedPnl(), book.unrealizedPnl(), book.comprehensivePnl(), mode);
                }
            }
        }
        int expired = dayOrderExpirer != null ? dayOrderExpirer.getAsInt() : 0;
        // The new session's OPEN = the state at rollover detection (post-gap): the overnight
        // move is open − previous close, intraday is live total − open. Persisted so the split
        // survives restarts.
        ConsolidatedRisk openSnapshot = risk.get();
        BigDecimal open = openSnapshot != null ? openSnapshot.total().comprehensivePnl() : closeTotal;
        if (jdbc != null) {
            jdbc.update("""
                    insert into firm_equity (day, total_pnl, open_pnl, feed_mode) values (?, ?, ?, ?)
                    on conflict (day, feed_mode) do update set open_pnl = excluded.open_pnl
                    """, newDay, open, open, io.jethro.messaging.Provenance.mode().name());
        }
        // Anchor first, then advance the day — a crash in between re-runs the rollover (upserts
        // make that idempotent) rather than losing it.
        previousCloseTotal = closeTotal;
        openTotal = open;
        currentDay = newDay;
        bufferedRisk = openSnapshot;
        bufferedMarks = marks.marks();
        log.info("EOD: session {} closed at firm P&L {} ({} DAY orders expired); session {} open at {} "
                        + "(overnight {})",
                ended, closeTotal.toPlainString(), expired, newDay, open.toPlainString(),
                open.subtract(closeTotal).toPlainString());
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
        BigDecimal total = snapshot != null ? snapshot.total().comprehensivePnl() : previousCloseTotal;
        return total.subtract(previousCloseTotal);
    }

    /** Overnight leg of today's P&L (session open − previous close), or null before the
     *  first boundary of this run (no open captured — disclosed, never guessed). */
    public BigDecimal overnightPnl() {
        BigDecimal open = openTotal;
        return open != null ? open.subtract(previousCloseTotal) : null;
    }

    /** Intraday leg of today's P&L (live total − session open), or null without an open. */
    public BigDecimal intradayPnl() {
        BigDecimal open = openTotal;
        if (open == null) {
            return null;
        }
        ConsolidatedRisk snapshot = risk.get();
        BigDecimal total = snapshot != null ? snapshot.total().comprehensivePnl() : open;
        return total.subtract(open);
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
                "select day, total_pnl from firm_equity where feed_mode = ? order by day desc limit ?",
                (rs, i) -> new Object[]{rs.getObject("day", LocalDate.class), rs.getBigDecimal("total_pnl")},
                io.jethro.messaging.Provenance.mode().name(), limit + 1); // one extra so the oldest shown row still has a day-P&L
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
