package io.muniworld.crawl;

import io.muniworld.ingest.HttpFetcher;
import io.muniworld.ingest.RawArtifact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The ADR-0008 polite crawl layer: wraps the raw {@link HttpFetcher} to (1) honour {@code robots.txt} for our
 * user-agent, and (2) rate-limit per host. Robots files are fetched once per host and cached; a host that
 * disallows a path throws rather than fetches. Discovery/crawl (ADR-0015 Phase 2) fetches through this, never
 * the raw fetcher, so public disclosure is retrieved lawfully and gently — private analysis, no redistribution.
 */
@Component
public final class PoliteFetcher {

    private static final Logger log = LoggerFactory.getLogger(PoliteFetcher.class);

    private final HttpFetcher http;
    private final String userAgent;
    private final HostRateLimiter limiter;
    private final ConcurrentHashMap<String, Robots> robotsByHost = new ConcurrentHashMap<>();

    public PoliteFetcher(HttpFetcher http,
                         @Value("${muni.http.user-agent:muni-world/0.1 (+municipal-data-collection)}") String userAgent,
                         @Value("${muni.crawl.min-interval-ms:2000}") long minIntervalMs) {
        this.http = http;
        this.userAgent = userAgent;
        this.limiter = new HostRateLimiter(minIntervalMs);
    }

    /** Fetch politely: robots-check for our UA, then per-host throttle, then the real GET. */
    public RawArtifact fetch(String sourceId, String url) {
        URI uri = URI.create(url);
        String host = uri.getHost();
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (!robotsFor(host, uri).allowed(path)) {
            throw new IllegalStateException("robots.txt disallows " + path + " on " + host + " for " + userAgent);
        }
        limiter.throttle(host);
        return http.fetch(sourceId, url);
    }

    private Robots robotsFor(String host, URI uri) {
        return robotsByHost.computeIfAbsent(host, h -> {
            String robotsUrl = uri.getScheme() + "://" + h + "/robots.txt";
            try {
                RawArtifact a = http.fetch("robots:" + h, robotsUrl);
                return Robots.parse(new String(a.body()), userAgent);
            } catch (RuntimeException e) {
                log.info("no usable robots.txt for {} ({}) — proceeding (allow-all)", h, e.toString());
                return Robots.parse("", userAgent);   // no robots → allow, but still rate-limited
            }
        });
    }
}
