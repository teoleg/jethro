package io.jethro.app.trading;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The proxy-rescaling that keeps seeded real history continuous with the instrument's price level;
 *  return-based covariance is invariant to the scale (ADR-0038). */
class HistorySeederTest {

    @Test
    void scalesTheProxyLastCloseToTheTargetLevel() {
        double[] spy = {440, 445, 450}; // SPY closes; ES level ~5450
        double scale = HistorySeeder.scaleToLevel(spy, 5450.0);
        assertEquals(5450.0, spy[spy.length - 1] * scale, 1e-9);
    }

    @Test
    void degenerateInputsReturnUnitScale() {
        assertEquals(1.0, HistorySeeder.scaleToLevel(new double[]{}, 100.0), 0);
        assertEquals(1.0, HistorySeeder.scaleToLevel(new double[]{0.0}, 100.0), 0);
        assertEquals(1.0, HistorySeeder.scaleToLevel(new double[]{10.0}, 0.0), 0);
        assertEquals(1.0, HistorySeeder.scaleToLevel(null, 100.0), 0);
    }

    @Test
    void scaleIsPositiveAndPreservesReturns() {
        double[] closes = {185, 188, 189};
        double scale = HistorySeeder.scaleToLevel(closes, 190.0);
        // ratio of consecutive scaled closes == ratio of raw closes (returns unchanged).
        assertEquals(188.0 / 185.0, (188 * scale) / (185 * scale), 1e-12);
    }
}
