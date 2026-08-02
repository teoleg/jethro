package io.muniworld.crawl;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-host politeness throttle (ADR-0008): at most one request per host every {@code minIntervalMs}, so a
 * crawl never hammers an origin. The wait computation is a pure function ({@link #waitMillis}) for testing;
 * {@link #throttle} applies it by sleeping. Thread-safe.
 */
public final class HostRateLimiter {

    private final long minIntervalMs;
    private final ConcurrentHashMap<String, Long> lastRequestMs = new ConcurrentHashMap<>();

    public HostRateLimiter(long minIntervalMs) {
        this.minIntervalMs = minIntervalMs;
    }

    /** How long a request to {@code host} must wait given the last request time and now (pure, testable). */
    public long waitMillis(String host, long nowMs) {
        Long last = lastRequestMs.get(host);
        if (last == null) {
            return 0;
        }
        long since = nowMs - last;
        return since >= minIntervalMs ? 0 : minIntervalMs - since;
    }

    /** Block until this host is allowed again, then record the request time. */
    public void throttle(String host) {
        long wait = waitMillis(host, System.currentTimeMillis());
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastRequestMs.put(host, System.currentTimeMillis());
    }
}
