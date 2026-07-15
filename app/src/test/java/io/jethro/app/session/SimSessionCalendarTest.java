package io.jethro.app.session;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The compressed sim calendar (ADR-0027): one trading day per simSecondsPerDay wall seconds. */
class SimSessionCalendarTest {

    @Test
    void dayRollsExactlyEverySimSecondsPerDay() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        LocalDate first = LocalDate.of(2026, 7, 14);
        var cal = new SimSessionCalendar(first, 120.0, clock::get);

        assertEquals(first, cal.sessionDay(), "starts on the anchor day");
        clock.addAndGet(119_999);
        assertEquals(first, cal.sessionDay(), "1ms before the boundary is still day 0");
        clock.addAndGet(1);
        assertEquals(first.plusDays(1), cal.sessionDay(), "120s elapsed = next session");
        clock.addAndGet(10 * 120_000);
        assertEquals(first.plusDays(11), cal.sessionDay(), "days accumulate linearly");
    }

    @Test
    void clockGoingBackwardsNeverRewindsTheDay() {
        AtomicLong clock = new AtomicLong(500_000L);
        var cal = new SimSessionCalendar(LocalDate.of(2026, 1, 1), 60.0, clock::get);
        clock.set(400_000L); // NTP step back below the start
        assertEquals(LocalDate.of(2026, 1, 1), cal.sessionDay(), "clamped at the anchor");
    }
}
