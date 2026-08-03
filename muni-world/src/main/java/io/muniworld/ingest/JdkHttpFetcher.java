package io.muniworld.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The real HTTP fetcher (java.net.http) — descriptive User-Agent identifying the project (ADR-0008), connect
 * + request timeouts so a slow origin can't hang a worker, and it follows normal redirects. Politeness
 * (robots, per-host rate limits, conditional GETs) layers on top as the connectors mature; this is the base.
 */
@Component
public final class JdkHttpFetcher implements HttpFetcher {

    private static final Logger log = LoggerFactory.getLogger(JdkHttpFetcher.class);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final String userAgent;

    public JdkHttpFetcher(@Value("${muni.http.user-agent:muni-world/0.1 (+municipal-data-collection)}") String userAgent) {
        this.userAgent = userAgent;
    }

    @Override
    public RawArtifact fetch(String sourceId, String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", userAgent)
                    .timeout(Duration.ofSeconds(45))
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() / 100 != 2) {
                throw new IOException("HTTP " + resp.statusCode() + " for " + url);
            }
            String ct = resp.headers().firstValue("content-type").orElse("application/octet-stream");
            RawArtifact a = RawArtifact.of(sourceId, url, ct, resp.body());
            log.info("fetched {} ({} bytes, {}) sha256={}", url, a.size(), ct, a.sha256().substring(0, 12));
            return a;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("fetch failed: " + url, e);
        }
    }
}
