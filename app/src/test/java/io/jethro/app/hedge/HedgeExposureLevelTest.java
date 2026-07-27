package io.jethro.app.hedge;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ADR-0105 habitual-net level: the median of an axis's own |net exposure| history, sampled on
 * the hedge cooldown clock. Every figure below is hand-computable — the whole point of the control
 * is that the band is the desk's own measured behaviour, not a number anybody chose.
 */
class HedgeExposureLevelTest {

    private static final String AXIS = "EQUITY";

    /** Sampling every 60s, a 4-deep window, speaking from 3 observations. */
    private static HedgeExposureLevel level() {
        return new HedgeExposureLevel(60_000, 4, 3);
    }

    @Test
    void saysNothingUntilItHasMinSampleObservations() {
        HedgeExposureLevel l = level();
        l.observe(AXIS, new BigDecimal("1000"), 0);
        l.observe(AXIS, new BigDecimal("3000"), 60_000);
        assertTrue(l.habitualUsd(AXIS).isEmpty(), "two samples — no measurement, no claim");
        l.observe(AXIS, new BigDecimal("2000"), 120_000);
        // {1000, 2000, 3000} → median 2000.00
        assertEquals(0, new BigDecimal("2000.00").compareTo(l.habitualUsd(AXIS).orElseThrow()));
    }

    @Test
    void theMedianOfAnEvenCountIsTheExactMeanOfTheTwoCentralObservations() {
        HedgeExposureLevel l = level();
        // {1000, 2000, 3000, 4000} → (2000 + 3000)/2 = 2500.00, exact
        l.observe(AXIS, new BigDecimal("4000"), 0);
        l.observe(AXIS, new BigDecimal("1000"), 60_000);
        l.observe(AXIS, new BigDecimal("3000"), 120_000);
        l.observe(AXIS, new BigDecimal("2000"), 180_000);
        assertEquals(0, new BigDecimal("2500.00").compareTo(l.habitualUsd(AXIS).orElseThrow()));
    }

    @Test
    void onlyTheMagnitudeIsRetained_aShortBookCarriesExposureToo() {
        HedgeExposureLevel l = level();
        l.observe(AXIS, new BigDecimal("-3000"), 0);
        l.observe(AXIS, new BigDecimal("-1000"), 60_000);
        l.observe(AXIS, new BigDecimal("2000"), 120_000);
        // {1000, 2000, 3000} → 2000.00: the side of the book is not the size of the exposure
        assertEquals(0, new BigDecimal("2000.00").compareTo(l.habitualUsd(AXIS).orElseThrow()));
    }

    @Test
    void aSampleOfferedInsideTheIntervalIsIgnored() {
        HedgeExposureLevel l = level();
        l.observe(AXIS, new BigDecimal("1000"), 0);
        l.observe(AXIS, new BigDecimal("900000"), 1_000);   // a REST poll 1s later
        l.observe(AXIS, new BigDecimal("900000"), 30_000);  // and another 29s after that
        l.observe(AXIS, new BigDecimal("2000"), 60_000);
        l.observe(AXIS, new BigDecimal("3000"), 120_000);
        assertEquals(3, l.samples(AXIS), "one sample per cooldown, whatever the poll rate");
        assertEquals(0, new BigDecimal("2000.00").compareTo(l.habitualUsd(AXIS).orElseThrow()));
    }

    @Test
    void theWindowIsBoundedAndTheOldestObservationDropsOut() {
        HedgeExposureLevel l = level();
        for (int i = 0; i < 5; i++) {
            // 1000, 2000, 3000, 4000, 5000 — the first falls out of the 4-deep window
            l.observe(AXIS, new BigDecimal((i + 1) * 1000), i * 60_000L);
        }
        assertEquals(4, l.samples(AXIS));
        // {2000, 3000, 4000, 5000} → (3000 + 4000)/2 = 3500.00
        assertEquals(0, new BigDecimal("3500.00").compareTo(l.habitualUsd(AXIS).orElseThrow()));
    }

    @Test
    void axesKeepSeparateMemories() {
        HedgeExposureLevel l = level();
        for (int i = 0; i < 3; i++) {
            l.observe("EQUITY", new BigDecimal("1000"), i * 60_000L);
        }
        assertEquals(0, new BigDecimal("1000.00").compareTo(l.habitualUsd("EQUITY").orElseThrow()));
        assertTrue(l.habitualUsd("RATES").isEmpty());
        assertEquals(0, l.samples("RATES"));
    }
}
