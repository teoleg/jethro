package io.jethro.app.session;

import java.time.LocalDate;
import java.util.function.LongSupplier;

/**
 * Compressed session calendar for sim runs (ADR-0026/0027): one trading day elapses every
 * {@code simSecondsPerDay} wall seconds, matching the factor simulator's time compression
 * exactly — so the measurement layer (daily closes, VaR window, outcome horizons' day
 * boundaries) accrues history at sim speed. Days are synthetic sequential dates anchored at
 * {@code firstDay}; the wiring anchors past any already-persisted history so a restart never
 * rewrites a closed day.
 */
public final class SimSessionCalendar implements TradingCalendar {

    private final LocalDate firstDay;
    private final long startMillis;
    private final long dayMillis;
    private final LongSupplier clock;

    public SimSessionCalendar(LocalDate firstDay, double simSecondsPerDay, LongSupplier clock) {
        if (simSecondsPerDay <= 0) {
            throw new IllegalArgumentException("simSecondsPerDay must be positive");
        }
        this.firstDay = firstDay;
        this.dayMillis = Math.max(1, Math.round(simSecondsPerDay * 1000));
        this.clock = clock;
        this.startMillis = clock.getAsLong();
    }

    @Override
    public LocalDate sessionDay() {
        long elapsed = Math.max(0, clock.getAsLong() - startMillis);
        return firstDay.plusDays(elapsed / dayMillis);
    }

    @Override
    public String description() {
        return "sim-compressed (1 trading day per " + dayMillis / 1000 + "s, anchored " + firstDay + ")";
    }
}
