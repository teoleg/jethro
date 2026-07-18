package io.jethro.app.trading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
 *
 * <p>Since ~2024 the endpoint rejects a bare request (HTTP 401/429) unless it carries a Yahoo
 * session cookie and a matching <em>crumb</em>. We do the same one-time handshake a browser does:
 * hit the finance homepage to pick up the session cookie (stored by a {@link CookieManager}), then
 * fetch a crumb bound to it, then send both on every chart request. Best-effort — a blocked
 * handshake just means the history seed logs the real status and the covariance warms from the feed.
 */
public final class YahooHistoryClient {

    private static final Logger log = LoggerFactory.getLogger(YahooHistoryClient.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CHART = "https://query1.finance.yahoo.com/v8/finance/chart/";
    private static final String HOME = "https://finance.yahoo.com/";
    private static final String CRUMB_URL = "https://query2.finance.yahoo.com/v1/test/getcrumb";
    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36";

    /** One symbol's cleaned daily series: dates (epoch-day), closes (real units), volumes. */
    public record History(long[] epochDays, double[] closes, long[] volumes) {
    }

    private final HttpClient http;
    private final Duration timeout;
    private final String range;
    private volatile String crumb;     // fetched once, lazily; null when the handshake was blocked
    private volatile boolean primed;

    public YahooHistoryClient(Duration timeout, String range) {
        // A CookieManager stores Yahoo's Set-Cookie and resends it, so the session the crumb is
        // bound to survives across the handshake and every chart call on this client instance.
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL) // the homepage 30x-redirects to set cookies
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .build();
        this.timeout = timeout;
        this.range = range == null || range.isBlank() ? "5y" : range;
    }

    /** Fetches and parses one symbol's history; empty on any error (never fabricates data). */
    public Optional<History> fetch(String yahooSymbol) {
        try {
            ensureSession();
            String url = CHART + yahooSymbol.replace("^", "%5E") + "?interval=1d&range=" + range
                    + (crumb != null ? "&crumb=" + URLEncoder.encode(crumb, StandardCharsets.UTF_8) : "");
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .timeout(timeout)
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Yahoo history {}: HTTP {}{} — {}", yahooSymbol, response.statusCode(),
                        crumb == null ? " (no crumb — handshake was blocked)" : "",
                        snippet(response.body()));
                return Optional.empty();
            }
            return parseChart(response.body());
        } catch (Exception e) {
            log.warn("Yahoo history {}: {}", yahooSymbol, e.toString());
            return Optional.empty();
        }
    }

    /**
     * One-time cookie + crumb handshake, mirroring a browser: (1) GET the finance homepage so the
     * {@link CookieManager} captures Yahoo's session cookie, (2) GET a crumb bound to that cookie.
     * Idempotent and best-effort — on failure {@code crumb} stays null and chart calls still try
     * (some regions serve without a crumb), while {@link #fetch} logs the resulting status.
     */
    private void ensureSession() {
        if (primed) {
            return;
        }
        synchronized (this) {
            if (primed) {
                return;
            }
            try {
                http.send(HttpRequest.newBuilder(URI.create(HOME))
                        .header("User-Agent", USER_AGENT).timeout(timeout).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                HttpResponse<String> cr = http.send(HttpRequest.newBuilder(URI.create(CRUMB_URL))
                        .header("User-Agent", USER_AGENT).header("Accept", "text/plain")
                        .timeout(timeout).GET().build(), HttpResponse.BodyHandlers.ofString());
                String body = cr.body() == null ? "" : cr.body().trim();
                if (cr.statusCode() == 200 && !body.isEmpty() && !body.contains("<") && body.length() < 64) {
                    crumb = body;
                    log.info("Yahoo history: session primed, crumb acquired");
                } else {
                    log.warn("Yahoo history: crumb handshake failed (HTTP {}, body {}) — "
                            + "chart calls will try without a crumb", cr.statusCode(), snippet(body));
                }
            } catch (Exception e) {
                log.warn("Yahoo history: crumb handshake error ({}) — chart calls will try without a crumb",
                        e.toString());
            } finally {
                primed = true; // only attempt the handshake once per client, success or not
            }
        }
    }

    private static String snippet(String body) {
        if (body == null || body.isBlank()) {
            return "<empty body>";
        }
        String s = body.strip().replaceAll("\\s+", " ");
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
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
