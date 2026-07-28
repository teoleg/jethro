package io.jethro.app.risk;

import io.jethro.app.risk.DailyCloseSeries.Close;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0073 — the daily close series' admissibility rule, on exact worked numbers.
 *
 * <p>The defect this encodes: before ADR-0073 {@code daily_close} was keyed {@code (day, instrument)}
 * with no feed mode, so a SIM session's closes and a LIVE session's closes shared one series. The
 * close-to-close "return" across the boundary was the ratio of two unrelated price levels — on the
 * live book that was a fabricated +72% day between the seed's AAPL at 190.000000 and the live feed's
 * at 326.950000, followed by −41.9% back to the sim's 189.859198 — and the EWMA(λ=0.94) estimator
 * behind per-name vol and VaR weights exactly those most recent observations most heavily.
 */
class DailyCloseSeriesTest {

    private static final LocalDate D1 = LocalDate.of(2026, 7, 16);
    private static final LocalDate D2 = LocalDate.of(2026, 7, 17);
    private static final LocalDate D3 = LocalDate.of(2026, 7, 20);
    private static final LocalDate D4 = LocalDate.of(2026, 7, 26);
    private static final LocalDate D5 = LocalDate.of(2026, 7, 27);

    /**
     * Worked example, round numbers so every return is exact.
     *
     * <pre>
     *   SEED 100.000   SEED 101.000   SIM 101.500   SIM 102.515
     *        └──────────────┘ same stream → 101.000/100.000 − 1 = +0.01
     *                       └──────────┘ SEED → SIM handover  → DROPPED
     *                                   └───────────┘ same stream → 102.515/101.500 − 1 = +0.01
     * </pre>
     *
     * Two returns of exactly +1%. Read as one undifferentiated series it would have produced three,
     * the middle one a +0.495% step across a change of price process.
     */
    @Test
    void takesReturnsOnlyWithinAStream() {
        double[] returns = DailyCloseSeries.returns(List.of(
                new Close(D1, DailyCloseSeries.SEED, 100.000),
                new Close(D2, DailyCloseSeries.SEED, 101.000),
                new Close(D4, "SIM", 101.500),
                new Close(D5, "SIM", 102.515)));

        assertArrayEquals(new double[] {0.01, 0.01}, returns, 1e-12);
    }

    /**
     * The observed contamination itself: the seed hands over to a LIVE session at a price level 72%
     * away, and back to a SIM session 42% below that. Every one of those steps is a handover, so the
     * admissible return count is zero — the estimator sees a gap, not three phantom market days.
     */
    @Test
    void dropsEveryPhantomAtAFeedBoundary() {
        double[] returns = DailyCloseSeries.returns(List.of(
                new Close(D2, DailyCloseSeries.SEED, 190.000000),
                new Close(D3, "LIVE", 326.950000),
                new Close(D4, "SIM", 189.859198)));

        assertEquals(0, returns.length);
    }

    @Test
    void aHandoverIsNotAMarketMove() {
        assertTrue(DailyCloseSeries.sameStream("SIM", "SIM"));
        assertTrue(DailyCloseSeries.sameStream(DailyCloseSeries.SEED, DailyCloseSeries.SEED));
        assertFalse(DailyCloseSeries.sameStream(DailyCloseSeries.SEED, "SIM"));
        assertFalse(DailyCloseSeries.sameStream("SIM", "LIVE"));
        assertFalse(DailyCloseSeries.sameStream(null, "SIM"));
    }

    /** A non-positive earlier close cannot produce a return; the pair is skipped, not divided by. */
    @Test
    void skipsNonPositiveEarlierClose() {
        double[] returns = DailyCloseSeries.returns(List.of(
                new Close(D1, "SIM", 0.0),
                new Close(D2, "SIM", 101.000),
                new Close(D4, "SIM", 102.010)));

        assertArrayEquals(new double[] {0.01}, returns, 1e-12);
    }

    @Test
    void tooShortASeriesHasNoReturns() {
        assertEquals(0, DailyCloseSeries.returns(List.of()).length);
        assertEquals(0, DailyCloseSeries.returns(List.of(new Close(D1, "SIM", 100.0))).length);
        assertEquals(0, DailyCloseSeries.returns(null).length);
    }

    /** Always two entries so callers can bind a fixed {@code in (?, ?)}; the seed is always readable. */
    @Test
    void admissibleModesAreTheRunningModeAndTheSeed() {
        assertEquals(List.of("SIM", "SEED"), DailyCloseSeries.admissibleModes("SIM"));
        assertEquals(List.of("LIVE", "SEED"), DailyCloseSeries.admissibleModes("LIVE"));
        assertEquals(List.of("SEED", "SEED"), DailyCloseSeries.admissibleModes("SEED"));
        assertEquals(List.of("SEED", "SEED"), DailyCloseSeries.admissibleModes(null));
        assertEquals(List.of("SEED", "SEED"), DailyCloseSeries.admissibleModes("  "));
    }

    /** Where a day carries both, the session's own observation of its own tape beats the prior. */
    @Test
    void theRunningModesOwnCloseBeatsTheSeed() {
        assertTrue(DailyCloseSeries.preferOver(DailyCloseSeries.SEED, "SIM"));
        assertFalse(DailyCloseSeries.preferOver("SIM", DailyCloseSeries.SEED));
        assertFalse(DailyCloseSeries.preferOver("SIM", "SIM"));
        assertFalse(DailyCloseSeries.preferOver(DailyCloseSeries.SEED, DailyCloseSeries.SEED));
    }
}
