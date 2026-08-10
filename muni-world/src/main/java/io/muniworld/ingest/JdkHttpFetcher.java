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

    public JdkHttpFetcher(@Value("${muni.http.user-agent:muni-world/0.1 (+municipal-data-collection)}") String userAgent,
                          @Value("${muni.http.contact:}") String contact) {
        // The SEC's fair-access policy REQUIRES a declared User-Agent carrying a contact address, and
        // www.sec.gov returns 403 without one (data.sec.gov is more lenient — which is why the filing
        // list worked and the document fetch did not). Appending the contact is what makes the archive
        // reachable; it is also simply the polite thing an identified crawler does (ADR-0008).
        this.userAgent = contact == null || contact.isBlank() ? userAgent : userAgent + " contact " + contact.strip();
        if (contact == null || contact.isBlank()) {
            log.warn("no muni.http.contact set (MUNI_CONTACT_EMAIL) — sec.gov returns HTTP 403 to a "
                    + "User-Agent with no contact address. Set it in local.env to fetch SEC filings.");
        }
    }

    /** True when no contact address is configured — the known cause of an sec.gov 403. */
    public boolean contactMissing() {
        return !userAgent.contains("contact ");
    }

    /**
     * The body, decompressed when the origin gzipped it. {@code java.net.http} does not decode for you, so
     * asking for gzip without this hands the parser compressed bytes — and N-PORT filings are several MB,
     * which is exactly where the saving matters.
     */
    private static byte[] decode(HttpResponse<byte[]> resp) throws IOException {
        String enc = resp.headers().firstValue("content-encoding").orElse("").toLowerCase();
        if (!enc.contains("gzip")) {
            return resp.body();
        }
        try (java.util.zip.GZIPInputStream in =
                     new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(resp.body()))) {
            return in.readAllBytes();
        }
    }

    @Override
    public RawArtifact fetch(String sourceId, String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", userAgent)
                    .header("Accept-Encoding", "gzip, deflate")   // the SEC asks crawlers to accept it
                    .header("Accept", "*/*")
                    .timeout(Duration.ofSeconds(45))
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() / 100 != 2) {
                throw new IOException("HTTP " + resp.statusCode() + " for " + url);
            }
            String ct = resp.headers().firstValue("content-type").orElse("application/octet-stream");
            RawArtifact a = RawArtifact.of(sourceId, url, ct, decode(resp));
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
