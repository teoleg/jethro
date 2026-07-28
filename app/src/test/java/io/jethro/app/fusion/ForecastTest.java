package io.jethro.app.fusion;

import io.jethro.app.social.SocialSignal;
import io.jethro.domain.Side;
import io.jethro.trading.algo.hypothesis.Hypothesis;
import io.jethro.trading.algo.strategy.TradeSignal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact-value tests for ADR-0055 phase-2 forecast normalisation: Carver scaling, the cap, ordinal
 *  mapping, and each source adapter. Everything is bounded to [-20,+20] and dimensionless. */
class ForecastTest {

    @Test
    void clampBoundsToCap() {
        assertEquals(20.0, Forecast.clamp(1000), 1e-12);
        assertEquals(-20.0, Forecast.clamp(-1000), 1e-12);
        assertEquals(7.5, Forecast.clamp(7.5), 1e-12);
        assertEquals(0.0, Forecast.clamp(Double.NaN), 1e-12);
        assertEquals(0.0, Forecast.clamp(Double.POSITIVE_INFINITY), 1e-12);
    }

    @Test
    void continuousScaleHitsTargetAtExpectedAbs() {
        // A reading equal to E|raw| scales to exactly TARGET_ABS (10).
        assertEquals(10.0, ForecastScaler.scale(3.0, 3.0), 1e-12);
        // Double the expected reading → double the forecast (20), still within the cap.
        assertEquals(20.0, ForecastScaler.scale(6.0, 3.0), 1e-12);
        // Beyond that it caps.
        assertEquals(20.0, ForecastScaler.scale(30.0, 3.0), 1e-12);
        // Non-positive expected-abs → no view (never divide by zero).
        assertEquals(0.0, ForecastScaler.scale(5.0, 0.0), 1e-12);
    }

    @Test
    void ordinalStepsEvenly() {
        assertEquals(5.0, ForecastScaler.stepped(1, 5.0), 1e-12);
        assertEquals(-15.0, ForecastScaler.stepped(-3, 5.0), 1e-12);
    }

    @Test
    void strategyForecastSignedBySide() {
        TradeSignal buy = new TradeSignal("AAPL", Side.BUY, BigDecimal.TEN, BigDecimal.TEN,
                BigDecimal.valueOf(300), 6.0); // |z|=6, expAbsZ=3 → magnitude 20, long
        assertEquals(20.0, SourceForecasts.fromStrategy(buy, 3.0).value(), 1e-12);
        TradeSignal sell = new TradeSignal("ES", Side.SELL, BigDecimal.TEN, BigDecimal.TEN,
                BigDecimal.valueOf(-150), 1.5); // |z|=1.5, expAbsZ=3 → 5, short
        assertEquals(-5.0, SourceForecasts.fromStrategy(sell, 3.0).value(), 1e-12);
    }

    @Test
    void hypothesisConvictionMapsToFivesTensFifteens() {
        assertEquals(15.0, SourceForecasts.fromHypothesis("AAPL", Side.BUY,
                Hypothesis.Conviction.HIGH, 5.0).value(), 1e-12);
        assertEquals(-5.0, SourceForecasts.fromHypothesis("AAPL", Side.SELL,
                Hypothesis.Conviction.LOW, 5.0).value(), 1e-12);
    }

    @Test
    void socialScalesWithChannelsAndSilencesPumps() {
        var bull = new SocialSignal("NVDA", true, "TECH", "BULLISH", 3, 40, false, "s");
        assertEquals(12.0, SourceForecasts.fromSocial(bull, 4.0).value(), 1e-12); // 3 channels × 4
        var pump = new SocialSignal("XYZ", true, "TECH", "BULLISH", 9, 200, true, "s");
        assertEquals(0.0, SourceForecasts.fromSocial(pump, 4.0).value(), 1e-12, "a suspected pump is no view");
        var neutral = new SocialSignal("AAPL", true, "TECH", "NEUTRAL", 2, 10, false, "s");
        assertEquals(0.0, SourceForecasts.fromSocial(neutral, 4.0).value(), 1e-12);
    }

    @Test
    void learnedContributesOnlyWhenItShips() {
        assertEquals(0.0, SourceForecasts.fromLearned("AAPL", 0.7, 0.1, false, 20.0).value(), 1e-12);
        // ships: edge = 0.7 − 0.1 = 0.6 → 0.6 × 20 = 12.
        assertEquals(12.0, SourceForecasts.fromLearned("AAPL", 0.7, 0.1, true, 20.0).value(), 1e-9);
    }

    @Test
    void trendScoreMapsOntoTheHouseConvention() {
        // ADR-0066: the sensor's score has expected |value| ≈ 1 ("one typical trend"), so a typical
        // reading must land on TARGET_ABS — the same conviction a typical firing of any other source is.
        assertEquals(10.0, SourceForecasts.fromTrend("AAPL", 1.0, Forecast.TARGET_ABS).value(), 1e-12);
        assertEquals(-10.0, SourceForecasts.fromTrend("AAPL", -1.0, Forecast.TARGET_ABS).value(), 1e-12);
        assertEquals(5.0, SourceForecasts.fromTrend("AAPL", 0.5, Forecast.TARGET_ABS).value(), 1e-12);
        assertEquals("trend", SourceForecasts.fromTrend("AAPL", 0.5, Forecast.TARGET_ABS).source());
        // An exceptional trend caps like everything else, and a non-finite reading is no view.
        assertEquals(20.0, SourceForecasts.fromTrend("AAPL", 9.0, Forecast.TARGET_ABS).value(), 1e-12);
        assertEquals(0.0, SourceForecasts.fromTrend("AAPL", Double.NaN, Forecast.TARGET_ABS).value(), 1e-12);
        assertEquals(0.0, SourceForecasts.fromTrend("AAPL", 0.0, Forecast.TARGET_ABS).value(), 1e-12);
    }

    @Test
    void reversionScoreMapsOntoTheHouseConvention() {
        // ADR-0070: same convention as the trend sensor — expected |score| ≈ 1 ("one typical stretch"),
        // and the sign is ALREADY the traded direction (the forecaster fades before it publishes), so
        // this mapper must not flip it a second time.
        assertEquals(10.0, SourceForecasts.fromReversion("AAPL", 1.0, Forecast.TARGET_ABS).value(), 1e-12);
        assertEquals(-10.0, SourceForecasts.fromReversion("AAPL", -1.0, Forecast.TARGET_ABS).value(), 1e-12);
        assertEquals("reversion", SourceForecasts.fromReversion("AAPL", 0.5, Forecast.TARGET_ABS).source());
        // It is a SEPARATE source from trend — the two are combined and weighted independently, never
        // collapsed into one view.
        assertNotEquals(SourceForecasts.fromTrend("AAPL", 1.0, Forecast.TARGET_ABS).source(),
                SourceForecasts.fromReversion("AAPL", 1.0, Forecast.TARGET_ABS).source());
        assertEquals(20.0, SourceForecasts.fromReversion("AAPL", 9.0, Forecast.TARGET_ABS).value(), 1e-12);
        assertEquals(0.0, SourceForecasts.fromReversion("AAPL", Double.NaN, Forecast.TARGET_ABS).value(), 1e-12);
        assertEquals(0.0, SourceForecasts.fromReversion("AAPL", 0.0, Forecast.TARGET_ABS).value(), 1e-12);
    }

    @Test
    void everyForecastStaysBounded() {
        assertTrue(Math.abs(SourceForecasts.fromLearned("X", 1.0, 0.0, true, 100.0).value()) <= Forecast.CAP);
    }
}
