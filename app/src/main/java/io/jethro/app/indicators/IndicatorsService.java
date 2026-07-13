package io.jethro.app.indicators;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Polls a curated set of major indices/ETFs (US + international) from Yahoo for the top-bar
 * market-indicators strip (ADR-0023) — context only, never tradeable positions or the risk
 * ledger. Self-contained (separate from the trading feed): its own poll loop and cache, so it
 * can't affect the market path (invariant 7). Delayed/unofficial and dev-only, like the feed.
 * Fetches fail gracefully to empty (e.g. offline sim) — the strip just shows what it has.
 */
public final class IndicatorsService implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(IndicatorsService.class);
    private static final String BASE = "https://query1.finance.yahoo.com/v8/finance/chart/";
    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36";
    private static final Pattern PRICE = Pattern.compile("\"regularMarketPrice\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern PREV = Pattern.compile("\"chartPreviousClose\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern PREV2 = Pattern.compile("\"previousClose\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");

    /** One indicator tile. {@code price}/{@code changePercent} are null when there's no data
     *  yet (or the fetch is failing) — the strip shows the symbol with an "N/A" placeholder
     *  so every configured index/ETF is always visible. */
    public record Indicator(String symbol, String label, String price, Double changePercent) {
    }

    private final IndicatorsProperties props;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    // Last-known good value per symbol — kept so a transient fetch failure doesn't flip a tile
    // back to N/A; a symbol only shows N/A until its first successful fetch.
    private final java.util.Map<String, Indicator> lastGood = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile List<Indicator> latest;
    private volatile ScheduledExecutorService scheduler;

    public IndicatorsService(IndicatorsProperties props) {
        this.props = props;
        this.latest = allSymbols(); // every symbol visible from t=0, as N/A until fetched
    }

    /** All configured symbols in order, using last-known value or an N/A placeholder. */
    private List<Indicator> allSymbols() {
        List<Indicator> out = new ArrayList<>();
        props.symbolsOrDefault().forEach((symbol, label) ->
                out.add(lastGood.getOrDefault(symbol, new Indicator(symbol, label, null, null))));
        return out;
    }

    public List<Indicator> latest() {
        return latest;
    }

    @Override
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "market-indicators");
            t.setDaemon(true);
            return t;
        });
        long period = props.pollSecondsOrDefault();
        scheduler.scheduleWithFixedDelay(this::poll, 2, period, TimeUnit.SECONDS);
        log.info("market indicators started: {} symbols, every {}s (Yahoo, delayed, context only)",
                props.symbolsOrDefault().size(), period);
    }

    private void poll() {
        try {
            int ok = 0;
            for (Map.Entry<String, String> e : props.symbolsOrDefault().entrySet()) {
                var got = fetch(e.getKey(), e.getValue());
                if (got.isPresent()) {
                    lastGood.put(e.getKey(), got.get());
                    ok++;
                }
            }
            // Always publish every configured symbol (last-known or N/A) so the strip is complete.
            latest = allSymbols();
            // Visibility (the request IS being made): log the hit rate; loud if none resolved.
            log.info("market indicators: {}/{} symbols have data{}",
                    ok, props.symbolsOrDefault().size(),
                    ok == 0 ? " — NONE resolved (check /api/indicators/probe for the raw Yahoo response)" : "");
        } catch (Throwable t) {
            log.debug("indicators poll failed: {}", t.toString());
        }
    }

    /** One-shot raw fetch for diagnostics (/api/indicators/probe): the actual URL, HTTP status,
     *  and the first bytes of the body — so a stuck strip can be diagnosed on the host. */
    public ProbeResult probe(String symbol) {
        String url = BASE + symbol.replace("^", "%5E") + "?interval=1d&range=5d";
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url))
                    .header("User-Agent", USER_AGENT).timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString());
            String body = r.body() == null ? "" : r.body();
            return new ProbeResult(url, r.statusCode(), body.substring(0, Math.min(400, body.length())), null);
        } catch (Exception e) {
            return new ProbeResult(url, -1, null, e.toString());
        }
    }

    public record ProbeResult(String url, int status, String bodySnippet, String error) {
    }

    private Optional<Indicator> fetch(String symbol, String label) {
        try {
            // Index symbols start with '^' (^GSPC, ^IXIC…), which is illegal in a URI and makes
            // URI.create throw — percent-encode it (^ → %5E) so indices actually resolve.
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE + symbol.replace("^", "%5E") + "?interval=1d&range=5d"))
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofSeconds(8))
                    .GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            return parse(symbol, label, response.body());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Extracts last level + previous close → % change. Package-visible + pure for testing. */
    static Optional<Indicator> parse(String symbol, String label, String json) {
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
        double changePct = 0.0;
        Matcher prev = PREV.matcher(json);
        if (!prev.find()) {
            prev = PREV2.matcher(json);
        }
        if (prev.reset(json).find()) {
            BigDecimal prevClose = new BigDecimal(prev.group(1));
            if (prevClose.signum() > 0) {
                changePct = px.subtract(prevClose)
                        .divide(prevClose, 6, RoundingMode.HALF_EVEN)
                        .multiply(BigDecimal.valueOf(100)).doubleValue();
            }
        }
        return Optional.of(new Indicator(symbol, label, px.toPlainString(), changePct));
    }

    @Override
    public void stop() {
        var s = scheduler;
        if (s != null) {
            s.shutdownNow();
            scheduler = null;
        }
    }

    @Override
    public boolean isRunning() {
        return scheduler != null;
    }
}
