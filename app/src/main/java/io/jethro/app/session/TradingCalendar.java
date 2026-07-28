package io.jethro.app.session;

import java.time.LocalDate;

/**
 * The session calendar (ADR-0027): maps "now" to the trading day it belongs to, so daily
 * closes, firm equity, day-P&L attribution and DAY-order expiry all agree on what "a day"
 * is. Two implementations: {@link SimSessionCalendar} compresses days to match the sim's
 * time compression (a day per {@code sim-seconds-per-day} wall seconds — VaR history
 * accrues at sim speed), {@link WallClockSessionCalendar} follows real dates in a
 * configured zone for live feeds.
 */
public interface TradingCalendar {

    /** The trading day the current instant belongs to. Monotonic non-decreasing. */
    LocalDate sessionDay();

    /**
     * Whether the market session currently permits OPENING (risk-adding) trades (ADR-0115). Default is
     * always-open: a continuous tape ({@link SimSessionCalendar} sim/replay) trades around the clock,
     * so only a real wall-clock calendar restricts to session hours. The pre-trade guardrail consults
     * this to block new exposure outside the session while still letting positions flatten.
     */
    default boolean isTradingSessionOpen() {
        return true;
    }

    /** Human-readable description for logs and the API (which calendar, what cadence). */
    String description();
}
