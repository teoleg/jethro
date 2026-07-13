package io.jethro.app.trading;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finnhub yield-curve parsing (ADR-0024): tolerant of the plausible payload shapes, never
 * throws; unknown shapes yield an empty map so the caller falls back to the sim curve.
 */
class FinnhubYieldCurveClientTest {

    @Test
    void parsesSnapshotListShape() {
        String json = """
            {"code":"US","data":[
              {"d":"2024-06-01","value":[
                {"t":"1Y","v":5.0},{"t":"2Y","v":4.8},{"t":"5Y","v":4.5},
                {"t":"10Y","v":4.3},{"t":"30Y","v":4.4}]}
            ]}
            """;
        Map<String, Double> m = FinnhubYieldCurveClient.parseLatestByTenor(json);
        assertEquals(5.0, m.get("1Y"));
        assertEquals(4.5, m.get("5Y"));
        assertEquals(4.4, m.get("30Y"));
    }

    @Test
    void parsesFlatDataShape() {
        String json = "{\"data\":[{\"t\":\"2Y\",\"v\":4.8},{\"t\":\"10Y\",\"v\":4.3}]}";
        Map<String, Double> m = FinnhubYieldCurveClient.parseLatestByTenor(json);
        assertEquals(4.8, m.get("2Y"));
        assertEquals(4.3, m.get("10Y"));
    }

    @Test
    void parsesBareArrayShapeAndUppercasesTenor() {
        String json = "[{\"t\":\"10y\",\"v\":4.31}]";
        Map<String, Double> m = FinnhubYieldCurveClient.parseLatestByTenor(json);
        assertEquals(4.31, m.get("10Y"), "tenor label normalised to upper-case");
    }

    @Test
    void garbageOrGatedResponsesYieldEmpty() {
        assertTrue(FinnhubYieldCurveClient.parseLatestByTenor("{}").isEmpty());
        assertTrue(FinnhubYieldCurveClient.parseLatestByTenor("not json").isEmpty());
        assertTrue(FinnhubYieldCurveClient.parseLatestByTenor(
                "{\"error\":\"You don't have access to this resource.\"}").isEmpty(), "premium/gated → empty");
    }
}
