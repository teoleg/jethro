package io.muniworld.crawl;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The per-host rate limiter (ADR-0008) and the HTML link extractor used by EMMA discovery (ADR-0015). */
class CrawlUtilsTest {

    @Test
    void rateLimiterSpacesRequestsPerHost() {
        HostRateLimiter l = new HostRateLimiter(2000);
        assertEquals(0, l.waitMillis("new-host", System.currentTimeMillis()), "first request never waits");
        l.throttle("h");   // records now (min interval elapsed since 'never' → no sleep)
        long w = l.waitMillis("h", System.currentTimeMillis());
        assertTrue(w > 0 && w <= 2000, "an immediate re-request must wait up to the interval, got " + w);
        assertEquals(0, l.waitMillis("h", System.currentTimeMillis() + 5000), "after the interval, no wait");
    }

    @Test
    void extractsAndFiltersAbsoluteLinks() {
        String html = "<a href=\"/Document/OS123.pdf\">Official Statement</a>"
                + " <a href='https://x.example/other'>o</a>"
                + " <a href=\"#top\">skip</a> <img src=\"logo.png\">";
        String base = "https://emma.msrb.org/Security/Details/649122AB1";

        List<String> all = HtmlLinks.absoluteLinks(html, base);
        assertTrue(all.contains("https://emma.msrb.org/Document/OS123.pdf"), "relative resolved against base");
        assertTrue(all.contains("https://x.example/other"), "absolute kept");
        assertFalse(all.stream().anyMatch(u -> u.contains("#top")), "in-page anchor dropped");

        List<String> pdfs = HtmlLinks.matching(html, base, Pattern.compile("(?i)\\.pdf(\\?|$)"));
        assertEquals(List.of("https://emma.msrb.org/Document/OS123.pdf"), pdfs, "only the OS PDF matches");
    }
}
