package io.jethro.app.session;

import io.jethro.trading.riskpnl.ConsolidatedRisk;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The EOD boundary (ADR-0027), persistence-free slice: rollover fires exactly once per day
 * change, expires DAY orders, and re-anchors "today's P&L" at the close. (The DB writes are
 * the same upsert pattern the recorder already exercises; the boundary logic is what's new.)
 */
class EodServiceTest {

    private static final class MutableCalendar implements TradingCalendar {
        volatile LocalDate day;

        MutableCalendar(LocalDate day) {
            this.day = day;
        }

        @Override
        public LocalDate sessionDay() {
            return day;
        }

        @Override
        public String description() {
            return "test";
        }
    }

    private static ConsolidatedRisk risk(String totalPnl) {
        var totals = new ConsolidatedRisk.Totals(BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal(totalPnl), BigDecimal.ZERO, BigDecimal.ZERO);
        return new ConsolidatedRisk(0L, totals, List.of(), List.of(), List.of());
    }

    @Test
    void rolloverExpiresDayOrdersAndReanchorsTodayPnl() {
        var calendar = new MutableCalendar(LocalDate.of(2026, 7, 14));
        var pnl = new AtomicReference<>(risk("1250.50"));
        var expiries = new AtomicInteger();
        var eod = new EodService(calendar, null, pnl::get, List::of, () -> {
            expiries.incrementAndGet();
            return 2;
        });

        // Intraday: no anchor yet → today's P&L is the whole running total.
        assertEquals(0, new BigDecimal("1250.50").compareTo(eod.todayPnl()));

        eod.checkOnce();
        assertEquals(0, expiries.get(), "no boundary — no sweep");

        calendar.day = calendar.day.plusDays(1);
        eod.checkOnce();
        assertEquals(1, expiries.get(), "session close sweeps DAY orders once");
        assertEquals(calendar.day, eod.sessionDay());
        assertEquals(0, new BigDecimal("1250.50").compareTo(eod.previousCloseTotal()),
                "the ended day's total becomes the anchor");

        // The new day starts flat, then measures only the move since the close.
        assertEquals(0, BigDecimal.ZERO.compareTo(eod.todayPnl()));
        pnl.set(risk("1300.50"));
        assertEquals(0, new BigDecimal("50.00").compareTo(eod.todayPnl()),
                "today's P&L = live total − previous close (1300.50 − 1250.50 = 50.00)");
    }

    @Test
    void multiDayJumpRollsOnceToTheCurrentDay() {
        var calendar = new MutableCalendar(LocalDate.of(2026, 7, 14));
        var expiries = new AtomicInteger();
        var eod = new EodService(calendar, null, () -> risk("0"), List::of, () -> {
            expiries.incrementAndGet();
            return 0;
        });

        calendar.day = calendar.day.plusDays(3); // process slept through boundaries
        eod.checkOnce();
        assertEquals(calendar.day, eod.sessionDay(), "lands on the current session");
        assertEquals(1, expiries.get(), "one sweep — skipped days had no marks to close");
    }
}
