package io.jethro.app.trading;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The proxy-level rescale (ADR-0038): an ETF proxy's history (e.g. SPY ~470) is rescaled so its
 * last close matches the instrument's own level (ES ~5450), keeping the seeded tape continuous
 * with the sim — without distorting returns, which are all the covariance uses.
 */
class YahooHistorySeederTest {

    @Test
    void rescalesTheProxyLastCloseToTheInstrumentLevel() {
        double[] spy = {460.0, 465.0, 470.0};
        double scale = YahooHistorySeeder.scaleToLevel(spy, 5450.0);
        assertEquals(5450.0, spy[2] * scale, 1e-6, "last seeded close lands at the instrument level");
        // returns are invariant to the scaling — the covariance sees the real proxy dynamics.
        double rawRet = Math.log(spy[2] / spy[1]);
        double scaledRet = Math.log((spy[2] * scale) / (spy[1] * scale));
        assertEquals(rawRet, scaledRet, 1e-12, "scaling must not change returns");
    }

    @Test
    void degenerateInputsFallBackToUnitScale() {
        assertEquals(1.0, YahooHistorySeeder.scaleToLevel(new double[]{}, 100.0), 0);
        assertEquals(1.0, YahooHistorySeeder.scaleToLevel(new double[]{0.0}, 100.0), 0);
        assertEquals(1.0, YahooHistorySeeder.scaleToLevel(new double[]{10.0}, 0.0), 0);
    }

    @Test
    void anEquityProxyIsItselfSoTheScaleIsNearOne() {
        // AAPL history ~189 with a configured start ~190 → scale ≈ 1 (minimal continuity nudge).
        double scale = YahooHistorySeeder.scaleToLevel(new double[]{185, 188, 189}, 190.0);
        assertTrue(scale > 0.99 && scale < 1.02, "equity-on-itself needs almost no rescale: " + scale);
    }
}
