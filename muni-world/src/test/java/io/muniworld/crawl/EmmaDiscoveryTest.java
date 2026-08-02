package io.muniworld.crawl;

import io.muniworld.ingest.HttpFetcher;
import io.muniworld.ingest.RawArtifact;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * EMMA discovery (ADR-0015 Phase 2) over a stub fetcher — no network: it reads the security page politely and
 * returns the candidate OS links, and it honours robots.txt (a disallowed path throws rather than fetches).
 */
class EmmaDiscoveryTest {

    private static final String TEMPLATE = "https://emma.msrb.org/Security/Details/{cusip}";
    private static final String OS_PATTERN = "(?i)(\\.pdf(\\?|$)|/Document/)";

    /** Stub: robots.txt → the given rules; the security page → HTML with an OS link. */
    private static HttpFetcher stub(String robots, String pageHtml) {
        return (sourceId, url) -> {
            String body = url.endsWith("/robots.txt") ? robots : pageHtml;
            return RawArtifact.of(sourceId, url, "text/html", body.getBytes(StandardCharsets.UTF_8));
        };
    }

    @Test
    void discoversOsCandidatesFromTheSecurityPage() {
        HttpFetcher http = stub("", "<a href=\"/Document/OS.pdf\">Official Statement</a><a href=\"/x\">x</a>");
        PoliteFetcher polite = new PoliteFetcher(http, "muni-world/0.1", 0);   // 0 interval → no sleeping
        EmmaDiscovery d = new EmmaDiscovery(polite, TEMPLATE, OS_PATTERN);

        EmmaDiscovery.Result r = d.discover("649122AB1");

        assertEquals("https://emma.msrb.org/Security/Details/649122AB1", r.securityUrl());
        assertEquals(List.of("https://emma.msrb.org/Document/OS.pdf"), r.candidates(),
                "only the OS document link is a candidate");
    }

    @Test
    void robotsDisallowBlocksTheFetch() {
        HttpFetcher http = stub("User-agent: *\nDisallow: /Security", "<a href=\"/Document/OS.pdf\">OS</a>");
        PoliteFetcher polite = new PoliteFetcher(http, "muni-world/0.1", 0);
        EmmaDiscovery d = new EmmaDiscovery(polite, TEMPLATE, OS_PATTERN);

        assertThrows(IllegalStateException.class, () -> d.discover("649122AB1"),
                "robots disallows /Security → discovery must refuse, not crawl");
    }
}
