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
import java.util.ArrayList;
import java.util.List;

/**
 * Telegram Bot-API adapter (ADR-0050 Phase 2b) — reads public channels/groups the bot has been added
 * to via {@code getUpdates} (a free, no-scraping path). Each channel post / message maps to a
 * {@link SocialPost}; the channel is {@code tg:<username>}. Telegram gives no follower count, so a
 * Telegram source is credible only through the CURATED registry (a trusted channel is TRUSTED there) —
 * an uncurated channel stays UNTRUSTED and cannot corroborate, which is the correct default for an
 * open messaging network. The bot token comes from config/env; blank → the source reports unhealthy
 * ("no token") and pulls nothing. Best-effort and fail-open; disabled by default (sim is the default).
 */
public final class TelegramSocialFeed implements SocialFeed {

    private static final Logger log = LoggerFactory.getLogger(TelegramSocialFeed.class);

    private static final ObjectMapper MAPPER = new ObjectMapper(); // one shared parser, not one per poll
    private final String baseUrl;
    private final String token;
    private final HttpClient http;
    private long offset; // getUpdates cursor so a message is read once
    private volatile SocialSourceStatus status =
            new SocialSourceStatus("telegram", false, 0, 0, "not polled yet");

    public TelegramSocialFeed(String baseUrl, String token, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token == null ? "" : token.trim();
        this.http = HttpClient.newBuilder()
                .proxy(ProxySelector.getDefault())
                .connectTimeout(timeout)
                .build();
    }

    @Override
    public List<SocialSourceStatus> health() {
        return List.of(status);
    }

    @Override
    public List<SocialPost> poll(List<String> universe, long nowMillis) {
        if (token.isBlank()) {
            status = new SocialSourceStatus("telegram", false, nowMillis, 0,
                    "no bot token (set jethro.social.telegram-bot-token / TELEGRAM_BOT_TOKEN)");
            return List.of();
        }
        try {
            URI uri = URI.create(baseUrl + "/bot" + token + "/getUpdates?timeout=0&offset=" + (offset + 1));
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/json").GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                status = new SocialSourceStatus("telegram", false, nowMillis, 0, "HTTP " + resp.statusCode());
                return List.of();
            }
            List<SocialPost> posts = parse(resp.body(), nowMillis);
            offset = Math.max(offset, maxUpdateId(resp.body()));
            status = new SocialSourceStatus("telegram", true, nowMillis, posts.size(),
                    "reached " + baseUrl + " · " + posts.size() + " new");
            return posts;
        } catch (Exception e) {
            status = new SocialSourceStatus("telegram", false, nowMillis, 0,
                    "unreachable — " + e.getClass().getSimpleName());
            log.debug("telegram getUpdates skipped: {}", e.toString());
            return List.of();
        }
    }

    /** Map a getUpdates response to posts (channel posts + messages). Static for fixture testing. */
    static List<SocialPost> parse(String json, long nowMillis) throws Exception {
        List<SocialPost> out = new ArrayList<>();
        JsonNode result = MAPPER.readTree(json).path("result");
        if (!result.isArray()) {
            return out;
        }
        for (JsonNode u : result) {
            JsonNode msg = u.has("channel_post") ? u.get("channel_post")
                    : u.has("message") ? u.get("message") : null;
            if (msg == null) {
                continue;
            }
            String text = msg.path("text").asText("");
            if (text.isBlank()) {
                continue;
            }
            JsonNode chat = msg.path("chat");
            String channelName = chat.path("username").asText(chat.path("title").asText("unknown"));
            String author = msg.path("from").path("username").asText(channelName);
            long ts = msg.path("date").asLong(nowMillis / 1000) * 1000;
            String id = "tg-" + msg.path("message_id").asText(String.valueOf(ts));
            // No follower/verify signal on Telegram → credibility comes from the curated registry tier.
            out.add(new SocialPost(id, "telegram", "tg:" + channelName, author, 0, false, 0, ts, text));
        }
        return out;
    }

    static long maxUpdateId(String json) throws Exception {
        long max = 0;
        for (JsonNode u : MAPPER.readTree(json).path("result")) {
            max = Math.max(max, u.path("update_id").asLong(0));
        }
        return max;
    }
}
