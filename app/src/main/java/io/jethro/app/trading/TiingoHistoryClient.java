package io.jethro.app.trading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Fetches daily EOD history from Tiingo (REAL data, free tier, API-key gated) for the boot history
 * seed (ADR-0038/0023) — replaces the now-defunct Yahoo history channel. Equities/ETFs use
 * {@code /tiingo/daily/<ticker>/prices} (adjusted close, so splits/dividends don't create phantom
 * jumps in the return series); FX pairs (6 lowercase letters, e.g. {@code eurusd}) use
 * {@code /tiingo/fx/<ticker>/prices?resampleFreq=1day}. The token comes from config/env and is sent
 * as an {@code Authorization: Token …} header (kept out of the URL/logs). Dev/demo only, ToS-limited,
 * never a production feed; empty on any error (never fabricates data). Jackson lives in the app layer,
 * so market-data stays dependency-free.
 */
public final class TiingoHistoryClient implements HistoryClient {

    private static final Logger log = LoggerFactory.getLogger(TiingoHistoryClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DAILY = "https://api.tiingo.com/tiingo/daily/";
    private static final String FX = "https://api.tiingo.com/tiingo/fx/";

    private final HttpClient http;
    private final Duration timeout;
    private final String token;
    private final int years;

    public TiingoHistoryClient(String token, Duration timeout, int years) {
        this.http = HttpClient.newBuilder().proxy(ProxySelector.getDefault())
                .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(timeout).build();
        this.timeout = timeout;
        this.token = token == null ? "" : token.trim();
        this.years = Math.max(1, years);
    }

    /** True when a token is configured — the seeder skips the fetch (and logs) when not. */
    public boolean configured() {
        return !token.isBlank();
    }

    @Override
    public Optional<History> fetch(String symbol) {
        if (token.isBlank()) {
            log.warn("Tiingo history {}: no token (set TIINGO_API_TOKEN / jethro.hedge.tiingo-token) — skipping", symbol);
            return Optional.empty();
        }
        boolean fx = symbol != null && symbol.matches("[a-z]{6}"); // eurusd, audusd, usdjpy, …
        String start = LocalDate.now().minusYears(years).toString();
        String url = fx
                ? FX + symbol + "/prices?resampleFreq=1day&startDate=" + start + "&format=json"
                : DAILY + symbol + "/prices?startDate=" + start + "&format=json";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Token " + token)
                    .header("Accept", "application/json")
                    .timeout(timeout).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("Tiingo history {}: HTTP {} — {}", symbol, resp.statusCode(), snippet(resp.body()));
                return Optional.empty();
            }
            return parse(resp.body(), fx);
        } catch (Exception e) {
            log.warn("Tiingo history {}: {}", symbol, e.toString());
            return Optional.empty();
        }
    }

    /** Parses a Tiingo prices array into a cleaned {@link History}; empty if unusable. Static + testable.
     *  Equities carry {@code adjClose} + {@code volume}; FX carries only {@code close} (no volume). */
    public static Optional<History> parse(String json, boolean fx) {
        try {
            JsonNode arr = MAPPER.readTree(json);
            if (!arr.isArray() || arr.isEmpty()) {
                return Optional.empty();
            }
            List<Long> days = new ArrayList<>();
            List<Double> px = new ArrayList<>();
            List<Long> vol = new ArrayList<>();
            for (JsonNode row : arr) {
                JsonNode dateNode = row.path("date");
                if (dateNode.isMissingNode() || dateNode.asText().length() < 10) {
                    continue;
                }
                // Prefer adjusted close (equities) so splits/dividends don't distort returns; FX has none.
                JsonNode closeNode = !fx && row.hasNonNull("adjClose") ? row.get("adjClose") : row.path("close");
                if (closeNode.isMissingNode() || closeNode.isNull()) {
                    continue;
                }
                double close = closeNode.asDouble();
                if (!(close > 0)) {
                    continue;
                }
                days.add(LocalDate.parse(dateNode.asText().substring(0, 10)).toEpochDay());
                px.add(close);
                JsonNode v = row.path("volume");
                vol.add(!v.isMissingNode() && !v.isNull() && v.asLong() >= 0 ? v.asLong() : 0L);
            }
            if (days.size() < 2) {
                return Optional.empty();
            }
            return Optional.of(new History(toLong(days), toDouble(px), toLong(vol)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String snippet(String body) {
        if (body == null || body.isBlank()) {
            return "<empty body>";
        }
        String s = body.strip().replaceAll("\\s+", " ");
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }

    private static long[] toLong(List<Long> list) {
        long[] out = new long[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }

    private static double[] toDouble(List<Double> list) {
        double[] out = new double[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }
}
