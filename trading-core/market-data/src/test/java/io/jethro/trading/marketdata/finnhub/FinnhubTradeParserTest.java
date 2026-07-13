package io.jethro.trading.marketdata.finnhub;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing Finnhub trade frames: exact prices (invariant 1), non-trade frames ignored. */
class FinnhubTradeParserTest {

    @Test
    void parsesMultipleTradesWithExactPrices() {
        String frame = "{\"data\":[{\"p\":190.125,\"s\":\"AAPL\",\"t\":1699999999000,\"v\":100},"
                + "{\"p\":430.5,\"s\":\"MSFT\",\"t\":1699999999500,\"v\":50}],\"type\":\"trade\"}";
        List<FinnhubTradeParser.Trade> trades = FinnhubTradeParser.parse(frame);
        assertEquals(2, trades.size());
        assertEquals("AAPL", trades.get(0).symbol());
        assertEquals(0, new BigDecimal("190.125").compareTo(trades.get(0).price())); // exact decimal
        assertEquals(1699999999000L, trades.get(0).epochMillis());
        assertEquals("MSFT", trades.get(1).symbol());
        assertEquals(0, new BigDecimal("430.5").compareTo(trades.get(1).price()));
    }

    @Test
    void ignoresPingAndNonTradeFrames() {
        assertTrue(FinnhubTradeParser.parse("{\"type\":\"ping\"}").isEmpty());
        assertTrue(FinnhubTradeParser.parse("{\"type\":\"error\",\"msg\":\"bad symbol\"}").isEmpty());
        assertTrue(FinnhubTradeParser.parse(null).isEmpty());
    }

    @Test
    void skipsNonPositivePrices() {
        String frame = "{\"data\":[{\"p\":0,\"s\":\"AAPL\",\"t\":1}],\"type\":\"trade\"}";
        assertTrue(FinnhubTradeParser.parse(frame).isEmpty());
    }
}
