package io.jethro.app.trading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jethro.trading.marketdata.sim.CurveMarkSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Live US Treasury yield curve from Finnhub (ADR-0024), as a {@link TreasuryCurveFetcher}.
 * Read-only, off the tick path, and passes through the shared {@link FinnhubRateLimiter} so it
 * shares the account-wide 60/min budget with the news feed. Defensive by contract: any HTTP
 * error, rate-limit skip, gated/premium response, or incomplete snapshot yields {@code null}
 * and the caller keeps the last good curve (or the sim at startup) — never a fabricated level.
 *
 * <p>NOTE (verify on host): Finnhub's bond endpoints may be premium-gated on the free tier. If
 * so this returns null and the platform stays on the sim curve (logged); the US Treasury direct
 * feed is the free fallback. Parsing is tolerant of a few plausible JSON shapes and unit-tested
 * against fixtures; the exact live shape is confirmed by the host run.
 */
public final class FinnhubYieldCurveClient implements TreasuryCurveFetcher {

    private static final Logger log = LoggerFactory.getLogger(FinnhubYieldCurveClient.class);
    private static final String URL = "https://finnhub.io/api/v1/bond/yield-curve?code=US&token=";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Finnhub tenor labels for our nodes (1/2/5/10/30Y), aligned to {@link CurveMarkSource#TENORS}. */
    private static final String[] NODE_LABELS = {"1Y", "2Y", "5Y", "10Y", "30Y"};

    private final HttpClient http;
    private final String token;
    private final Duration timeout;
    private final FinnhubRateLimiter limiter;

    public FinnhubYieldCurveClient(String token, Duration timeout, FinnhubRateLimiter limiter) {
        this.token = token;
        this.timeout = timeout;
        this.limiter = limiter;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public double[] fetchNodeZeros() {
        if (!limiter.tryAcquire()) {
            log.debug("finnhub yield-curve fetch skipped — REST budget spent this minute");
            return null;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(URL + enc(token)))
                    .timeout(timeout).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("finnhub yield-curve → HTTP {} (free tier may not include bond data — "
                        + "staying on the sim curve). Body: {}", resp.statusCode(), snippet(resp.body()));
                return null;
            }
            return toNodeZeros(parseLatestByTenor(resp.body()));
        } catch (Exception e) {
            log.warn("finnhub yield-curve fetch failed (staying on the sim curve): {}", e.toString());
            return null;
        }
    }

    /**
     * Extracts the latest tenor→yield(percent) snapshot from a Finnhub yield-curve payload,
     * tolerant of a few shapes: {@code {"data":[{"value":[{"t","v"}...]}]}} (snapshot list),
     * {@code {"data":[{"t","v"}...]}} (flat), or a bare {@code [{"t","v"}...]} array. Unknown
     * shapes yield an empty map (→ null curve → sim fallback).
     */
    static Map<String, Double> parseLatestByTenor(String json) {
        Map<String, Double> out = new LinkedHashMap<>();
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root == null) {
                return out;
            }
            JsonNode values = locateValueArray(root);
            if (values != null && values.isArray()) {
                for (JsonNode n : values) {
                    String tenor = n.path("t").asText(n.path("tenor").asText(""));
                    JsonNode v = n.has("v") ? n.get("v") : n.get("value");
                    if (!tenor.isBlank() && v != null && v.isNumber()) {
                        out.put(tenor.toUpperCase(java.util.Locale.ROOT), v.asDouble());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("finnhub yield-curve parse failed: {}", e.toString());
        }
        return out;
    }

    /** Finds the array of {t,v} points across the tolerated shapes. */
    private static JsonNode locateValueArray(JsonNode root) {
        if (root.isArray()) {
            return root;
        }
        JsonNode data = root.get("data");
        if (data != null && data.isArray() && !data.isEmpty()) {
            JsonNode first = data.get(0);
            if (first != null && first.has("value") && first.get("value").isArray()) {
                return first.get("value"); // snapshot-list shape → latest snapshot's points
            }
            return data; // flat shape: data is itself the {t,v} array
        }
        JsonNode value = root.get("value");
        return value != null && value.isArray() ? value : null;
    }

    /** Maps a tenor→yield(percent) snapshot to node zero rates (fraction); null if any node missing. */
    private static double[] toNodeZeros(Map<String, Double> byTenor) {
        if (byTenor.isEmpty()) {
            return null;
        }
        double[] zeros = new double[CurveMarkSource.TENORS.length];
        for (int i = 0; i < NODE_LABELS.length; i++) {
            Double pct = byTenor.get(NODE_LABELS[i]);
            if (pct == null) {
                return null; // incomplete snapshot — don't guess a node (finance-math rule)
            }
            zeros[i] = pct / 100.0; // percent → fraction
        }
        return zeros;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 160 ? body.substring(0, 160) : body;
    }
}
