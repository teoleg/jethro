package io.jethro.app.session;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** US trading days + the 17:00-ET futures-style session roll — exact 2026 dates. */
class UsTradingCalendarTest {

    @Test
    void knownHolidaysAndObservanceShiftsFor2026() {
        assertEquals(LocalDate.of(2026, 4, 5), UsTradingCalendar.easterSunday(2026));
        assertFalse(UsTradingCalendar.isTradingDay(LocalDate.of(2026, 4, 3)), "Good Friday 2026");
        assertFalse(UsTradingCalendar.isTradingDay(LocalDate.of(2026, 1, 19)), "MLK (3rd Mon Jan)");
        assertFalse(UsTradingCalendar.isTradingDay(LocalDate.of(2026, 5, 25)), "Memorial (last Mon May)");
        // July 4 2026 is a Saturday → observed Friday July 3.
        assertFalse(UsTradingCalendar.isTradingDay(LocalDate.of(2026, 7, 3)), "observed Independence Day");
        assertTrue(UsTradingCalendar.isTradingDay(LocalDate.of(2026, 7, 6)), "Monday after is a session");
        assertFalse(UsTradingCalendar.isTradingDay(LocalDate.of(2026, 11, 26)), "Thanksgiving");
        assertTrue(UsTradingCalendar.isTradingDay(LocalDate.of(2026, 11, 27)),
                "Black Friday is a (short) session — early closes not modelled, disclosed");
        assertFalse(UsTradingCalendar.isTradingDay(LocalDate.of(2026, 7, 11)), "a Saturday");
        assertTrue(UsTradingCalendar.isTradingDay(LocalDate.of(2026, 7, 14)), "a plain Tuesday");
    }

    private static WallClockSessionCalendar atEastern(String instant) {
        return new WallClockSessionCalendar(ZoneId.of("America/New_York"), 17,
                Clock.fixed(Instant.parse(instant), ZoneId.of("UTC")));
    }

    @Test
    void seventeenHundredRollAndNonTradingDaysNeverOwnASession() {
        // Tue 2026-07-14 16:59 ET (20:59Z, EDT) — still Tuesday's session.
        assertEquals(LocalDate.of(2026, 7, 14), atEastern("2026-07-14T20:59:00Z").sessionDay());
        // Tue 17:00 ET — rolled to Wednesday.
        assertEquals(LocalDate.of(2026, 7, 15), atEastern("2026-07-14T21:00:00Z").sessionDay());
        // Fri 2026-07-10 17:30 ET — the weekend never owns a session → Monday.
        assertEquals(LocalDate.of(2026, 7, 13), atEastern("2026-07-10T21:30:00Z").sessionDay());
        // Sunday afternoon (Globex pre-open) → Monday's session too.
        assertEquals(LocalDate.of(2026, 7, 13), atEastern("2026-07-12T18:00:00Z").sessionDay());
        // Thu 2026-04-02 18:00 ET, Good Friday next → rolls straight to Monday Apr 6.
        assertEquals(LocalDate.of(2026, 4, 6), atEastern("2026-04-02T22:00:00Z").sessionDay());
    }
}
