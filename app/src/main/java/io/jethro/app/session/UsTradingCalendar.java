package io.jethro.app.session;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * US exchange trading days (ADR-0027 calendar refinement): weekends plus the ten NYSE/CME
 * full-closure holidays, with Saturday→Friday / Sunday→Monday observance. Pure rules, no
 * data files — deterministic for any year (Easter via the anonymous Gregorian computus for
 * Good Friday). CONVENTION: the NYSE full-closure set (CME early-close days like Black
 * Friday still count as trading days — a session happens, just shorter; half-day close
 * times are a further refinement, disclosed, not modelled).
 */
public final class UsTradingCalendar {

    private static final Map<Integer, Set<LocalDate>> CACHE = new HashMap<>();

    private UsTradingCalendar() {
    }

    public static boolean isTradingDay(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return false;
        }
        return !holidays(date.getYear()).contains(date);
    }

    /** The next trading day at or after {@code date}. */
    public static LocalDate nextTradingDayOnOrAfter(LocalDate date) {
        LocalDate d = date;
        while (!isTradingDay(d)) {
            d = d.plusDays(1);
        }
        return d;
    }

    /** Full-closure holidays for a year, observance-shifted. */
    static synchronized Set<LocalDate> holidays(int year) {
        return CACHE.computeIfAbsent(year, y -> Set.of(
                observed(LocalDate.of(y, 1, 1)),                                    // New Year's Day
                nthWeekday(y, 1, DayOfWeek.MONDAY, 3),                              // MLK Day
                nthWeekday(y, 2, DayOfWeek.MONDAY, 3),                              // Presidents' Day
                easterSunday(y).minusDays(2),                                       // Good Friday
                LocalDate.of(y, 5, 31).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), // Memorial Day
                observed(LocalDate.of(y, 6, 19)),                                   // Juneteenth
                observed(LocalDate.of(y, 7, 4)),                                    // Independence Day
                nthWeekday(y, 9, DayOfWeek.MONDAY, 1),                              // Labor Day
                nthWeekday(y, 11, DayOfWeek.THURSDAY, 4),                           // Thanksgiving
                observed(LocalDate.of(y, 12, 25))));                                // Christmas
    }

    /** Saturday holidays are observed Friday; Sunday holidays Monday (NYSE rule). */
    private static LocalDate observed(LocalDate holiday) {
        return switch (holiday.getDayOfWeek()) {
            case SATURDAY -> holiday.minusDays(1);
            case SUNDAY -> holiday.plusDays(1);
            default -> holiday;
        };
    }

    private static LocalDate nthWeekday(int year, int month, DayOfWeek dow, int n) {
        return LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, dow));
    }

    /** Anonymous Gregorian computus — Easter Sunday for any Gregorian year. */
    static LocalDate easterSunday(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day);
    }
}
