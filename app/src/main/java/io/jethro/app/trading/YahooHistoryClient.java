package io.jethro.app.trading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Fetches daily OHLCV history from Yahoo's {@code /v8/finance/chart} endpoint (ADR-0032/0023):
 * dev/demo only, unofficial and ToS-limited — used to CAPTURE a real snapshot for the historical
 * sim, never a production feed. One GET per symbol returns aligned {@code timestamp[]},
 * {@code close[]} and {@code volume[]}; we keep only rows with a real close (Yahoo nulls
 * holidays/halts). Jackson lives in the app layer, so market-data stays dependency-free.
 */
public final class YahooHistoryClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "https://query1.finance.yahoo.com/v8/finance/chart/";
    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36";

    /** One symbol's cleaned daily series: dates (epoch-day), closes (real units), volumes. */
    public record History(long[] epochDays, double[] closes, long[] volumes) {
    }

    private final HttpClient http;
    private final Duration timeout;
    private final String range;

    public YahooHistoryClient(Duration timeout, String range) {
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.timeout = timeout;
        this.range = range == null || range.isBlank() ? "5y" : range;
    }

    /** Fetches and parses one symbol's history; empty on any error (never fabricates data). */
    public Optional<History> fetch(String yahooSymbol) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE + yahooSymbol.replace("^", "%5E")
                            + "?interval=1d&range=" + range))
                    .header("User-Agent", USER_AGENT)
                    .timeout(timeout)
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            return parseChart(response.body());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Parses a Yahoo chart body into a cleaned {@link History}; empty if unusable. Testable. */
    public static Optional<History> parseChart(String json) {
        try {
            JsonNode result = MAPPER.readTree(json).path("chart").path("result");
            if (!result.isArray() || result.isEmpty()) {
                return Optional.empty();
            }
            JsonNode r = result.get(0);
            JsonNode ts = r.path("timestamp");
            JsonNode quote = r.path("indicators").path("quote");
            if (!ts.isArray() || !quote.isArray() || quote.isEmpty()) {
                return Optional.empty();
            }
            JsonNode closes = quote.get(0).path("close");
            JsonNode volumes = quote.get(0).path("volume");
            List<Long> days = new ArrayList<>();
            List<Double> px = new ArrayList<>();
            List<Long> vol = new ArrayList<>();
            for (int i = 0; i < ts.size(); i++) {
                JsonNode c = closes.get(i);
                if (c == null || c.isNull()) {
                    continue; // Yahoo nulls a non-trading/halted day — drop it
                }
                double close = c.asDouble();
                if (!(close > 0)) {
                    continue;
                }
                days.add(ts.get(i).asLong() / 86_400L); // epoch-second → epoch-day
                px.add(close);
                JsonNode v = volumes != null ? volumes.get(i) : null;
                vol.add(v != null && !v.isNull() && v.asLong() >= 0 ? v.asLong() : 0L);
            }
            if (days.size() < 2) {
                return Optional.empty();
            }
            return Optional.of(new History(toLongArray(days), toDoubleArray(px), toLongArray(vol)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static long[] toLongArray(List<Long> list) {
        long[] out = new long[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }

    private static double[] toDoubleArray(List<Double> list) {
        double[] out = new double[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }
}
