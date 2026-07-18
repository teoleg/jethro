package io.jethro.app.trading;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing of Yahoo's chart history (transport is exercised only on a real box, ADR-0023). */
class YahooHistoryClientTest {

    @Test
    void parsesAndDropsNullCloses() {
        // day 2 (172800s) has a null close — a non-trading/halted day Yahoo pads; it must drop.
        String json = "{\"chart\":{\"result\":[{"
                + "\"timestamp\":[86400,172800,259200],"
                + "\"indicators\":{\"quote\":[{"
                + "\"close\":[100.0,null,102.0],"
                + "\"volume\":[1000,2000,3000]}]}}],\"error\":null}}";
        Optional<YahooHistoryClient.History> h = YahooHistoryClient.parseChart(json);
        assertTrue(h.isPresent());
        YahooHistoryClient.History hist = h.get();
        assertEquals(2, hist.closes().length, "the null-close row is dropped");
        assertEquals(1, hist.epochDays()[0], "epoch-seconds convert to epoch-day");
        assertEquals(3, hist.epochDays()[1]);
        assertEquals(100.0, hist.closes()[0], 1e-9);
        assertEquals(102.0, hist.closes()[1], 1e-9);
        assertEquals(3000, hist.volumes()[1]);
    }

    @Test
    void emptyOnUnusableBody() {
        assertTrue(YahooHistoryClient.parseChart("{\"chart\":{\"result\":[]}}").isEmpty());
        assertTrue(YahooHistoryClient.parseChart("not json").isEmpty());
    }
}
