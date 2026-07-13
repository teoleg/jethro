package io.jethro.trading.marketdata.yahoo;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing the Yahoo chart response — exact price (invariant 1), missing fields → empty. */
class YahooQuoteClientTest {

    private static final String OK = """
            {"chart":{"result":[{"meta":{"currency":"USD","symbol":"AAPL",
            "regularMarketPrice":190.125,"regularMarketTime":1699999999,"exchangeName":"NMS"}}],"error":null}}""";

    @Test
    void parsesPriceAndTimeExactly() {
        Optional<QuoteSource.Quote> q = YahooQuoteClient.parseChart(OK);
        assertTrue(q.isPresent());
        assertEquals(0, new BigDecimal("190.125").compareTo(q.get().price())); // exact decimal
        assertEquals(1699999999L, q.get().epochSeconds());
    }

    @Test
    void missingPriceYieldsEmpty() {
        assertTrue(YahooQuoteClient.parseChart(
                "{\"chart\":{\"result\":null,\"error\":{\"code\":\"Not Found\"}}}").isEmpty());
        assertTrue(YahooQuoteClient.parseChart("garbage").isEmpty());
        assertTrue(YahooQuoteClient.parseChart(null).isEmpty());
    }

    @Test
    void nonPositivePriceRejected() {
        assertTrue(YahooQuoteClient.parseChart(
                "{\"meta\":{\"regularMarketPrice\":0,\"regularMarketTime\":1}}").isEmpty());
    }

    @Test
    void missingTimeFallsBackToNow() {
        Optional<QuoteSource.Quote> q = YahooQuoteClient.parseChart("{\"regularMarketPrice\":12.5}");
        assertTrue(q.isPresent());
        assertTrue(q.get().epochSeconds() > 1_000_000_000L); // some sane wall-clock epoch
    }
}
