package io.muniworld.ingest;

/**
 * Fetches a URL into a {@link RawArtifact}. The one seam every connector (ADR-0004) fetches through, so the
 * real HTTP client ({@link JdkHttpFetcher}) can be swapped for a stub in tests and so politeness/limits
 * (ADR-0008) live in one place.
 */
public interface HttpFetcher {
    RawArtifact fetch(String sourceId, String url);
}
