package io.jethro.app.hypothesis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Live Finnhub news REST client (ADR-0024): {@code /news?category=general} for macro and
 * {@code /company-news?symbol=…&from=…&to=…} per name, on the free tier. Read-only, off the
 * tick path, and defensive by contract — any HTTP/parse failure returns an empty list and is
 * logged, never thrown (the hypothesis layer just gets no new items). Parsing is a pure
 * static method so it's unit-tested against fixture JSON without a network.
 */
public final class FinnhubNewsClient implements FinnhubNarrativeFeed.NewsSource {

    private static final Logger log = LoggerFactory.getLogger(FinnhubNewsClient.class);
    private static final String BASE = "https://finnhub.io/api/v1";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Look-back window for company news — a few days catches earnings without a huge payload. */
    private static final int COMPANY_LOOKBACK_DAYS = 3;

    private final HttpClient http;
    private final String token;
    private final Duration timeout;

    public FinnhubNewsClient(String token, Duration timeout) {
        this.token = token;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public List<FinnhubNarrativeFeed.NewsSource.Article> companyNews(String finnhubSymbol) {
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(COMPANY_LOOKBACK_DAYS);
        return get(BASE + "/company-news?symbol=" + enc(finnhubSymbol)
                + "&from=" + from + "&to=" + to + "&token=" + enc(token));
    }

    @Override
    public List<FinnhubNarrativeFeed.NewsSource.Article> generalNews() {
        return get(BASE + "/news?category=general&token=" + enc(token));
    }

    private List<FinnhubNarrativeFeed.NewsSource.Article> get(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.debug("finnhub news {} → HTTP {}", url.replace(token, "***"), resp.statusCode());
                return List.of();
            }
            return parse(resp.body());
        } catch (Exception e) {
            log.debug("finnhub news fetch failed ({}): {}", url.replace(token, "***"), e.toString());
            return List.of();
        }
    }

    /** Parses a Finnhub news JSON array into articles. Missing/odd fields are skipped, never fatal. */
    static List<FinnhubNarrativeFeed.NewsSource.Article> parse(String json) {
        List<FinnhubNarrativeFeed.NewsSource.Article> out = new ArrayList<>();
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root == null || !root.isArray()) {
                return out;
            }
            for (JsonNode n : root) {
                String headline = n.path("headline").asText("");
                if (headline.isBlank()) {
                    continue;
                }
                out.add(new FinnhubNarrativeFeed.NewsSource.Article(
                        n.path("id").asLong(0),
                        n.path("datetime").asLong(0),
                        headline,
                        n.path("summary").asText("")));
            }
        } catch (Exception e) {
            log.debug("finnhub news parse failed: {}", e.toString());
        }
        return out;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
