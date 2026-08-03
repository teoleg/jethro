package io.muniworld.crawl;

import io.muniworld.ingest.RawArtifact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * EMMA Official-Statement discovery (ADR-0015 Phase 2): given a CUSIP, fetch its EMMA security page
 * <em>politely</em> ({@link PoliteFetcher} — robots + rate limit, ADR-0008) and extract the candidate OS
 * document links, so the extractor no longer needs a hand-fed PDF URL.
 *
 * <p>EMMA is a JS-heavy site whose exact URL scheme and markup change over time, and it can't be reached from
 * the build sandbox — so the security-URL template and the OS-link pattern are <b>configuration</b>
 * ({@code muni.emma.security-url-template}, {@code muni.emma.os-link-pattern}), tuned on the Pi where EMMA is
 * reachable, rather than hard-coded assumptions. The mechanism (fetch → extract links → hand to the ADR-0015
 * extractor) is deterministic and tested; only the two patterns are environment-specific.
 */
@Component
public final class EmmaDiscovery {

    private static final Logger log = LoggerFactory.getLogger(EmmaDiscovery.class);

    private final PoliteFetcher fetcher;
    private final String urlTemplate;
    private final Pattern osLinkPattern;

    public EmmaDiscovery(
            PoliteFetcher fetcher,
            @Value("${muni.emma.security-url-template:https://emma.msrb.org/Security/Details/{cusip}}") String urlTemplate,
            @Value("${muni.emma.os-link-pattern:(?i)(\\.pdf(\\?|$)|officialstatement|/Document/|securitydocument)}")
            String osLinkPattern) {
        this.fetcher = fetcher;
        this.urlTemplate = urlTemplate;
        this.osLinkPattern = Pattern.compile(osLinkPattern);
    }

    /** What discovery found: the security page it read, and the candidate OS document links on it. */
    public record Result(String cusip, String securityUrl, List<String> candidates) {
    }

    /** Discover candidate OS document links for a CUSIP by reading its EMMA security page (politely). */
    public Result discover(String cusip) {
        String url = urlTemplate.replace("{cusip}", cusip);
        RawArtifact page = fetcher.fetch("emma-discover:" + cusip, url);
        String html = new String(page.body(), StandardCharsets.UTF_8);
        List<String> candidates = HtmlLinks.matching(html, url, osLinkPattern);
        log.info("EMMA discovery {}: {} candidate OS link(s) on {}", cusip, candidates.size(), url);
        return new Result(cusip, url, candidates);
    }

    /** Fetch a discovered document link politely (used to land the OS PDF before extraction). */
    public RawArtifact fetchDocument(String sourceId, String url) {
        return fetcher.fetch(sourceId, url);
    }
}
