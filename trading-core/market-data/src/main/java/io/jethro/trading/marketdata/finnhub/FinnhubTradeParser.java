package io.jethro.trading.marketdata.finnhub;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Finnhub trade WebSocket messages (ADR-0024). A trade frame looks like
 * {@code {"data":[{"p":190.12,"s":"AAPL","t":1699999999000,"v":100}],"type":"trade"}} —
 * {@code p} price, {@code s} symbol, {@code t} epoch MILLIS. Dependency-free (this module
 * stays lean): the flat trade objects are pulled out with a narrow regex. Prices are exact
 * {@link BigDecimal} at the boundary (invariant 1); non-trade frames (ping) yield nothing.
 */
public final class FinnhubTradeParser {

    /** One trade print: symbol, exact price, and its source epoch-millis timestamp. */
    public record Trade(String symbol, BigDecimal price, long epochMillis) {
    }

    private static final Pattern OBJ = Pattern.compile("\\{[^{}]*\\}");
    private static final Pattern SYM = Pattern.compile("\"s\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern PRICE = Pattern.compile("\"p\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern TIME = Pattern.compile("\"t\"\\s*:\\s*(\\d+)");

    public static List<Trade> parse(String json) {
        List<Trade> out = new ArrayList<>();
        if (json == null || !json.contains("\"trade\"")) {
            return out; // ping/subscribe-ack/other — no trades
        }
        Matcher objs = OBJ.matcher(json); // flat trade objects (no nesting) inside the data array
        while (objs.find()) {
            String obj = objs.group();
            Matcher s = SYM.matcher(obj);
            Matcher p = PRICE.matcher(obj);
            if (!s.find() || !p.find()) {
                continue;
            }
            BigDecimal px = new BigDecimal(p.group(1));
            if (px.signum() <= 0) {
                continue;
            }
            Matcher t = TIME.matcher(obj);
            long epochMillis = t.find() ? Long.parseLong(t.group(1)) : System.currentTimeMillis();
            out.add(new Trade(s.group(1), px, epochMillis));
        }
        return out;
    }
}
