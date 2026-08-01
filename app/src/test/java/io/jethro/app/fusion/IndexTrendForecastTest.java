package io.jethro.app.fusion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ADR-0130 market-index trend source maps its claim exactly like the per-name trend source (a trend is
 * a trend), carries the traded direction in its sign, and publishes under a distinct source key so the
 * combiner and the telemetry can measure it on its own.
 */
class IndexTrendForecastTest {

    @Test
    void indexTrendClaimMatchesTrendClaimAndCarriesDirection() {
        // Same units mapper as the per-name trend, so the sizing dials keep their meaning across sources.
        assertEquals(SourceForecasts.trendClaim(0.7, Forecast.TARGET_ABS),
                SourceForecasts.indexTrendClaim(0.7, Forecast.TARGET_ABS), 1e-12);

        // Sign is the traded direction; a non-finite score is a no-view, never NaN into sizing.
        assertTrue(SourceForecasts.indexTrendClaim(0.5, Forecast.TARGET_ABS) > 0, "market up → long");
        assertTrue(SourceForecasts.indexTrendClaim(-0.5, Forecast.TARGET_ABS) < 0, "market down → short");
        assertEquals(0.0, SourceForecasts.indexTrendClaim(Double.NaN, Forecast.TARGET_ABS), 0.0);
    }

    @Test
    void publishesUnderADistinctIndexTrendSource() {
        Forecast f = SourceForecasts.fromIndexTrend("AAPL", 0.9, Forecast.TARGET_ABS);
        assertEquals(SourceForecasts.INDEX_TREND, f.source(), "own source key — measured/weighted on its own");
        assertEquals("AAPL", f.instrument());
        assertTrue(f.value() > 0, "market up carried to the name as a long view");
        // Distinct from the per-name trend source so the two are never conflated in the blend/telemetry.
        assertTrue(!SourceForecasts.INDEX_TREND.equals(SourceForecasts.TREND));
    }
}
