package io.muniworld.ingest;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * An immutable fetched artifact — the ADR-0005 raw landing record: the original bytes plus provenance
 * (source, url, fetch time, content type, SHA-256). Everything downstream (parse → normalise → index)
 * carries this back, and identical content (same hash) dedupes to the same artifact.
 */
public record RawArtifact(String sourceId, String url, long fetchedAtEpochMs, String contentType,
                          String sha256, byte[] body) {

    public static RawArtifact of(String sourceId, String url, String contentType, byte[] body) {
        return new RawArtifact(sourceId, url, System.currentTimeMillis(), contentType, sha256(body), body);
    }

    public static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 is always present
        }
    }

    public int size() {
        return body == null ? 0 : body.length;
    }
}
