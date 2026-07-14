package io.jethro.app.session;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Real-time session calendar (ADR-0027): futures-style day roll — from {@code rollHour}
 * local time (default 17:00 ET, the CME settlement boundary) the clock belongs to the NEXT
 * trading day, and weekends/US holidays ({@link UsTradingCalendar}) never own a session:
 * Friday 17:30 → Monday's session (Globex opens Sunday evening FOR Monday); a Thursday
 * evening before Good Friday rolls straight to Monday. With a real feed nothing marks
 * while closed, so the roll mainly decides which session late prints and the EOD boundary
 * belong to. Half-day early closes are counted as full sessions (disclosed, not modelled).
 */
public final class WallClockSessionCalendar implements TradingCalendar {

    private final ZoneId zone;
    private final int rollHour;
    private final Clock clock;

    public WallClockSessionCalendar(ZoneId zone, int rollHour) {
        this(zone, rollHour, Clock.systemUTC());
    }

    WallClockSessionCalendar(ZoneId zone, int rollHour, Clock clock) {
        if (rollHour < 0 || rollHour > 23) {
            throw new IllegalArgumentException("rollHour must be 0..23");
        }
        this.zone = zone;
        this.rollHour = rollHour;
        this.clock = clock;
    }

    @Override
    public LocalDate sessionDay() {
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(zone);
        LocalDate candidate = now.toLocalDate();
        if (now.getHour() >= rollHour) {
            candidate = candidate.plusDays(1);
        }
        return UsTradingCalendar.nextTradingDayOnOrAfter(candidate);
    }

    @Override
    public String description() {
        return "wall-clock (" + zone + ", rolls " + String.format("%02d:00", rollHour)
                + ", US trading days)";
    }
}
