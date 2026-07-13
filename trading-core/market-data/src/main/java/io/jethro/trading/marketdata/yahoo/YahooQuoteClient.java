package io.jethro.trading.marketdata.yahoo;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal Yahoo Finance quote fetcher (ADR-0023): dev/demo only, poll not stream, unofficial
 * and ToS-limited — never a production/real-money feed. Uses the {@code /v8/finance/chart}
 * endpoint (one GET per symbol), which returns the last regular-market price without the
 * cookie+crumb dance the batch {@code /v7/quote} endpoint needs — fewer moving parts to break.
 *
 * <p>Dependency-free by design (this module stays lean): the two fields we need
 * ({@code regularMarketPrice}, {@code regularMarketTime}) are pulled from the JSON with a
 * narrow regex rather than a JSON library. Prices are exact {@link BigDecimal} at the boundary
 * (invariant 1). A non-200, a network error, or a missing price yields empty — the adapter then
 * counts a drop and skips that instrument rather than fabricating a mark.
 */
public final class YahooQuoteClient implements QuoteSource {

    private static final String BASE = "https://query1.finance.yahoo.com/v8/finance/chart/";
    // A browser-like UA reduces the chance of a bot-block; still unofficial and may break.
    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36";
    private static final Pattern PRICE =
            Pattern.compile("\"regularMarketPrice\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern TIME =
            Pattern.compile("\"regularMarketTime\"\\s*:\\s*(\\d+)");

    private final HttpClient http;
    private final Duration timeout;

    public YahooQuoteClient(Duration timeout) {
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.timeout = timeout;
    }

    @Override
    public Optional<QuoteSource.Quote> fetch(String yahooSymbol) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE + yahooSymbol + "?interval=1m&range=1d"))
                    .header("User-Agent", USER_AGENT)
                    .timeout(timeout)
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Optional.empty(); // 429/403/… — caller counts the drop and backs off
            }
            return parseChart(response.body());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Extracts the last price and its epoch-second timestamp from a chart response. */
    public static Optional<QuoteSource.Quote> parseChart(String json) {
        if (json == null) {
            return Optional.empty();
        }
        Matcher price = PRICE.matcher(json);
        if (!price.find()) {
            return Optional.empty();
        }
        BigDecimal px = new BigDecimal(price.group(1));
        if (px.signum() <= 0) {
            return Optional.empty();
        }
        Matcher time = TIME.matcher(json);
        long epochSeconds = time.find() ? Long.parseLong(time.group(1)) : System.currentTimeMillis() / 1000;
        return Optional.of(new QuoteSource.Quote(px, epochSeconds));
    }
}
