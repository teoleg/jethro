package io.jethro.app.discovery;

import io.jethro.app.social.SocialSourceStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Real RSS news adapter (ADR-0045/0050) — pulls headlines from the CURATED outlets you configure
 * ({@code jethro.discovery.outlets}: name → feed URL; major wires, an exchange feed, SEC EDGAR, …),
 * no scraping (the Yahoo lesson). Feeds the universe-discovery layer: which instruments the big
 * outlets are talking about, so untracked names can be proposed for the base list. Best-effort and
 * fail-open — an outlet that errors is logged and shown unreachable, never sinking the cycle. Read for
 * discovery/context only; never a number into sizing/risk, never an order (invariant 7 / ADR-0049).
 */
public final class RssNewsFeed {

    private static final Logger log = LoggerFactory.getLogger(RssNewsFeed.class);

    // Some outlets (notably SEC.gov) reject the default Java-http-client User-Agent with a 403 and
    // require a descriptive one with a contact URL — send one for every request.
    private static final String USER_AGENT = "jethro-discovery/1.0 (+https://github.com/teoleg/jethro)";

    private final Map<String, String> outlets; // outlet name → RSS URL
    private final HttpClient http;
    private volatile SocialSourceStatus status = new SocialSourceStatus("news:rss", false, 0, 0, "not polled yet");

    public RssNewsFeed(Map<String, String> outlets, Duration timeout) {
        this.outlets = Map.copyOf(outlets);
        // followRedirects(NORMAL): many feeds 301 http→https or to a CDN path; without it a redirect
        // reads as a non-2xx "unreachable". proxy(getDefault) keeps it working behind a corporate proxy.
        this.http = HttpClient.newBuilder().proxy(ProxySelector.getDefault())
                .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(timeout).build();
    }

    public SocialSourceStatus status() {
        return status;
    }

    public List<NewsItem> poll(long nowMillis) {
        List<NewsItem> out = new ArrayList<>();
        if (outlets.isEmpty()) {
            status = new SocialSourceStatus("news:rss", false, nowMillis, 0,
                    "no outlets configured (jethro.discovery.outlets)");
            return out;
        }
        boolean anyOk = false;
        String lastError = null;
        for (var e : outlets.entrySet()) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(e.getValue()))
                        .timeout(Duration.ofSeconds(8)).header("User-Agent", USER_AGENT)
                        .header("Accept", "application/rss+xml, application/xml, application/atom+xml").GET().build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() / 100 != 2) {
                    lastError = "HTTP " + resp.statusCode() + " on " + e.getKey();
                    continue;
                }
                anyOk = true;
                out.addAll(parse(resp.body(), e.getKey(), nowMillis));
            } catch (Exception ex) {
                lastError = ex.getClass().getSimpleName() + " on " + e.getKey();
                log.debug("rss {} skipped: {}", e.getKey(), ex.toString());
            }
        }
        status = new SocialSourceStatus("news:rss", anyOk, nowMillis, out.size(),
                anyOk ? "polled " + outlets.keySet() : "unreachable — " + (lastError == null ? "n/a" : lastError));
        return out;
    }

    /** Parse an RSS document into news items. Static + package-visible for fixture testing. XXE-safe. */
    static List<NewsItem> parse(String xml, String outlet, long nowMillis) throws Exception {
        List<NewsItem> out = new ArrayList<>();
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setExpandEntityReferences(false);
        DocumentBuilder db = dbf.newDocumentBuilder();
        var doc = db.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        NodeList items = doc.getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            if (!(items.item(i) instanceof Element item)) {
                continue;
            }
            String title = text(item, "title");
            String desc = text(item, "description");
            if (title.isBlank() && desc.isBlank()) {
                continue;
            }
            long ts = pubDate(text(item, "pubDate"), nowMillis);
            String link = text(item, "link");
            String id = "rss-" + outlet + "-" + (link.isBlank() ? Integer.toString((title + ts).hashCode()) : link);
            out.add(new NewsItem(id, outlet, ts, title, (title + " " + desc).trim(), link));
        }
        return out;
    }

    private static String text(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getParentNode() == parent) {
                return n.getTextContent() == null ? "" : n.getTextContent().trim();
            }
        }
        return "";
    }

    private static long pubDate(String raw, long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
        } catch (Exception e) {
            return fallback;
        }
    }
}
