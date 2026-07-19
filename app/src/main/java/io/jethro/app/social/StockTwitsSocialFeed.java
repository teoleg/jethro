package io.jethro.app.social;

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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * StockTwits public-stream adapter (ADR-0050 Phase 2) — a free, finance-native social source behind
 * the {@link SocialFeed} SPI. Polls a capped subset of the equity universe per cycle
 * ({@code /streams/symbol/{SYM}.json}) and maps each message to a {@link SocialPost}, carrying the
 * author's credibility signals (followers, official=verified, join-date age) so the corroboration
 * gate can weigh it. Best-effort and fail-open: a network/rate-limit/parse error on one symbol logs
 * and is skipped — the source never breaks the cycle (the composite tolerates a whole-source failure
 * too). Sentiment is NOT read from StockTwits' own tag; we classify from the post TEXT like every
 * other source (invariant 8, one code path). Never authenticates against a paid tier; disabled by
 * default (sim is the shipped default) — turn on with {@code jethro.social.sources=sim,stocktwits}.
 */
public final class StockTwitsSocialFeed implements SocialFeed {

    private static final Logger log = LoggerFactory.getLogger(StockTwitsSocialFeed.class);

    private final String baseUrl;
    private final int symbolsPerCycle;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private int cursor; // round-robins through the universe so all names get covered over cycles
    private volatile SocialSourceStatus status =
            new SocialSourceStatus("stocktwits", false, 0, 0, "not polled yet");

    @Override
    public List<SocialSourceStatus> health() {
        return List.of(status);
    }

    public StockTwitsSocialFeed(String baseUrl, int symbolsPerCycle, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.symbolsPerCycle = Math.max(1, symbolsPerCycle);
        this.http = HttpClient.newBuilder()
                .proxy(ProxySelector.getDefault()) // honour the environment's HTTPS proxy
                .connectTimeout(timeout)
                .build();
    }

    @Override
    public List<SocialPost> poll(List<String> universe, long nowMillis) {
        List<SocialPost> out = new ArrayList<>();
        if (universe == null || universe.isEmpty()) {
            return out;
        }
        int polled = 0;
        boolean anyOk = false;
        String lastError = null;
        List<String> symbols = new ArrayList<>();
        for (int i = 0; i < Math.min(symbolsPerCycle, universe.size()); i++) {
            String symbol = universe.get(Math.floorMod(cursor++, universe.size()));
            symbols.add(symbol);
            polled++;
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/streams/symbol/" + symbol + ".json"))
                        .timeout(Duration.ofSeconds(8))
                        .header("Accept", "application/json")
                        .GET().build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() / 100 != 2) {
                    lastError = "HTTP " + resp.statusCode() + " on " + symbol;
                    log.debug("stocktwits {} → HTTP {} (skipped)", symbol, resp.statusCode());
                    continue;
                }
                anyOk = true;
                out.addAll(parse(resp.body(), nowMillis));
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + " on " + symbol;
                log.debug("stocktwits {} skipped: {}", symbol, e.toString());
            }
        }
        String detail = anyOk ? "reached " + baseUrl + " · polled " + symbols
                : "unreachable — " + (lastError == null ? "no symbols" : lastError);
        status = new SocialSourceStatus("stocktwits", anyOk, nowMillis, out.size(), detail);
        return out;
    }

    /** Parse a StockTwits streams response into posts. Package-visible + static for fixture testing. */
    static List<SocialPost> parse(String json, long nowMillis) throws Exception {
        List<SocialPost> out = new ArrayList<>();
        JsonNode root = new ObjectMapper().readTree(json);
        JsonNode messages = root.path("messages");
        if (!messages.isArray()) {
            return out;
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (JsonNode m : messages) {
            JsonNode user = m.path("user");
            String username = user.path("username").asText("anon");
            int followers = user.path("followers").asInt(0);
            boolean official = user.path("official").asBoolean(false);
            int ageDays = accountAgeDays(user.path("join_date").asText(null), today);
            long ts = messageTimeMillis(m.path("created_at").asText(null), nowMillis);
            String body = m.path("body").asText("");
            String id = "st-" + m.path("id").asText(username + "-" + ts);
            out.add(new SocialPost(id, "stocktwits", "stocktwits:" + username, username,
                    followers, official, ageDays, ts, body));
        }
        return out;
    }

    private static int accountAgeDays(String joinDate, LocalDate today) {
        if (joinDate == null || joinDate.isBlank()) {
            return 0; // unknown join date → treated as brand-new (conservative for credibility)
        }
        try {
            return (int) Math.max(0, ChronoUnit.DAYS.between(LocalDate.parse(joinDate), today));
        } catch (DateTimeParseException e) {
            return 0;
        }
    }

    private static long messageTimeMillis(String createdAt, long fallback) {
        if (createdAt == null || createdAt.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(createdAt).toEpochMilli();
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }
}
