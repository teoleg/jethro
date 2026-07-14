package io.jethro.app.session;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Real-time session calendar (ADR-0027): the trading day is the calendar date in the
 * configured zone (default America/New_York — the universe is US-centric). v1 treats every
 * date as a session: with a real feed nothing marks on weekends so no spurious return
 * observations arise, but Friday→Monday is measured as ONE day's return rather than three
 * calendar days — the conservative, disclosed simplification. Exchange holiday calendars and
 * a 17:00-ET futures-style roll are the follow-up refinement, not silently faked here.
 */
public final class WallClockSessionCalendar implements TradingCalendar {

    private final ZoneId zone;

    public WallClockSessionCalendar(ZoneId zone) {
        this.zone = zone;
    }

    @Override
    public LocalDate sessionDay() {
        return LocalDate.now(zone);
    }

    @Override
    public String description() {
        return "wall-clock (" + zone + ", midnight roll)";
    }
}
