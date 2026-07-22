package io.jethro.trading.marketdata.alpaca;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Alpaca v2 market-data WebSocket messages (ADR-0056). A message is a JSON ARRAY of flat objects,
 * each tagged by {@code "T"}: a TRADE is {@code {"T":"t","S":"AAPL","p":324.58,"s":100,"t":"2026-07-21T
 * 14:30:00.12Z", ...}} — {@code S} symbol, {@code p} price, {@code t} an RFC-3339 timestamp (note: the
 * type tag {@code "T"} and time {@code "t"} differ only by case). Control frames ({@code "T":"success"},
 * {@code "error"}, {@code "subscription"}) and quotes ({@code "T":"q"}) yield no trades. Dependency-free
 * (this module stays lean, like the Finnhub parser): flat objects are pulled with a narrow regex; prices
 * are exact {@link BigDecimal} at the boundary (invariant 1). Alpaca symbols never leak past the gateway
 * (invariant 2) — they map to {@code instrumentId} in the adapter.
 */
public final class AlpacaTradeParser {

    /** One trade print: symbol, exact price, and its source epoch-millis timestamp. */
    public record Trade(String symbol, BigDecimal price, long epochMillis) {
    }

    private static final Pattern OBJ = Pattern.compile("\\{[^{}]*\\}");
    private static final Pattern TYPE = Pattern.compile("\"T\"\\s*:\\s*\"([a-zA-Z]+)\"");
    private static final Pattern SYM = Pattern.compile("\"S\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern PRICE = Pattern.compile("\"p\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern TIME = Pattern.compile("\"t\"\\s*:\\s*\"([^\"]+)\"");

    public static List<Trade> parse(String json, long nowMillis) {
        List<Trade> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        Matcher objs = OBJ.matcher(json); // flat objects (conditions use [] not {}, so this holds)
        while (objs.find()) {
            String obj = objs.group();
            Matcher type = TYPE.matcher(obj);
            if (!type.find() || !"t".equals(type.group(1))) {
                continue; // not a trade (quote / success / error / subscription)
            }
            Matcher s = SYM.matcher(obj);
            Matcher p = PRICE.matcher(obj);
            if (!s.find() || !p.find()) {
                continue;
            }
            BigDecimal px = new BigDecimal(p.group(1));
            if (px.signum() <= 0) {
                continue;
            }
            out.add(new Trade(s.group(1), px, timeMillis(obj, nowMillis)));
        }
        return out;
    }

    private static long timeMillis(String obj, long fallback) {
        Matcher t = TIME.matcher(obj);
        if (!t.find()) {
            return fallback;
        }
        try {
            return Instant.parse(t.group(1)).toEpochMilli(); // RFC-3339, nanos tolerated
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }
}
