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

    /** Human-readable description for logs and the API (which calendar, what cadence). */
    String description();
}
