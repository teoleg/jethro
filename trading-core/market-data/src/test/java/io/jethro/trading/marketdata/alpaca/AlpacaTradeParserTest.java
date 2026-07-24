package io.jethro.trading.marketdata.alpaca;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Alpaca v2 trade-message parsing (ADR-0056): trades extracted, control/quote frames ignored. */
class AlpacaTradeParserTest {

    private static final long NOW = 1_700_000_000_000L;

    @Test
    void parsesATradeWithSymbolPriceAndRfc3339Time() {
        String msg = "[{\"T\":\"t\",\"S\":\"AAPL\",\"i\":96921,\"x\":\"D\",\"p\":324.58,\"s\":100,"
                + "\"t\":\"2026-07-21T18:30:00.123456789Z\",\"c\":[\"@\"],\"z\":\"C\"}]";
        List<AlpacaTradeParser.Trade> trades = AlpacaTradeParser.parse(msg, NOW);
        assertEquals(1, trades.size());
        assertEquals("AAPL", trades.get(0).symbol());
        assertEquals(0, trades.get(0).price().compareTo(new BigDecimal("324.58")));
        assertEquals(Instant.parse("2026-07-21T18:30:00.123456789Z").toEpochMilli(),
                trades.get(0).epochMillis(), "RFC-3339 t → epoch millis; T (type) must not be read as time");
    }

    @Test
    void parsesMultipleTradesInOneArray() {
        String msg = "[{\"T\":\"t\",\"S\":\"MSFT\",\"p\":396.21,\"s\":50,\"t\":\"2026-07-21T18:30:01Z\"},"
                + "{\"T\":\"t\",\"S\":\"JPM\",\"p\":344.30,\"s\":10,\"t\":\"2026-07-21T18:30:02Z\"}]";
        List<AlpacaTradeParser.Trade> trades = AlpacaTradeParser.parse(msg, NOW);
        assertEquals(2, trades.size());
        assertEquals("MSFT", trades.get(0).symbol());
        assertEquals("JPM", trades.get(1).symbol());
    }

    @Test
    void ignoresControlAndQuoteFrames() {
        assertTrue(AlpacaTradeParser.parse("[{\"T\":\"success\",\"msg\":\"authenticated\"}]", NOW).isEmpty());
        assertTrue(AlpacaTradeParser.parse("[{\"T\":\"subscription\",\"trades\":[\"AAPL\"]}]", NOW).isEmpty());
        assertTrue(AlpacaTradeParser.parse("[{\"T\":\"error\",\"code\":401,\"msg\":\"not authenticated\"}]", NOW).isEmpty());
        assertTrue(AlpacaTradeParser.parse(
                "[{\"T\":\"q\",\"S\":\"AAPL\",\"bp\":324.5,\"ap\":324.6,\"t\":\"2026-07-21T18:30:00Z\"}]", NOW).isEmpty(),
                "a quote is not a trade");
    }

    @Test
    void badTimeFallsBackToNowAndBlankIsEmpty() {
        String msg = "[{\"T\":\"t\",\"S\":\"AAPL\",\"p\":324.58,\"t\":\"not-a-time\"}]";
        assertEquals(NOW, AlpacaTradeParser.parse(msg, NOW).get(0).epochMillis());
        assertTrue(AlpacaTradeParser.parse("", NOW).isEmpty());
    }
}
