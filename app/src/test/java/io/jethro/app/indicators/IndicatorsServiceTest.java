package io.jethro.app.indicators;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing an index chart response into a level + % change vs previous close. */
class IndicatorsServiceTest {

    @Test
    void computesPercentChangeFromPreviousClose() {
        // 5450 vs prev close 5400 → +0.9259%.
        String json = "{\"chart\":{\"result\":[{\"meta\":{\"regularMarketPrice\":5450,"
                + "\"chartPreviousClose\":5400,\"symbol\":\"^GSPC\"}}]}}";
        Optional<IndicatorsService.Indicator> i = IndicatorsService.parse("^GSPC", "S&P 500", json);
        assertTrue(i.isPresent());
        assertEquals("S&P 500", i.get().label());
        assertEquals("5450", i.get().price());
        assertEquals(0.9259, i.get().changePercent(), 0.001);
    }

    @Test
    void missingPreviousCloseGivesZeroChangeNotError() {
        Optional<IndicatorsService.Indicator> i = IndicatorsService.parse(
                "SPY", "SPY", "{\"regularMarketPrice\":545.12}");
        assertTrue(i.isPresent());
        assertEquals(0.0, i.get().changePercent());
    }

    @Test
    void missingPriceOrGarbageYieldsEmpty() {
        assertTrue(IndicatorsService.parse("^DJI", "Dow", "{\"error\":\"x\"}").isEmpty());
        assertTrue(IndicatorsService.parse("^DJI", "Dow", "not json").isEmpty());
        assertTrue(IndicatorsService.parse("^DJI", "Dow", null).isEmpty());
    }
}
