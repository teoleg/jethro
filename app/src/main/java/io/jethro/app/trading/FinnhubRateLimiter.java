package io.jethro.app.trading;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Shared budget for <b>all</b> Finnhub REST calls (ADR-0024). Finnhub's free-tier limit —
 * 60 calls/minute — is <b>account-wide</b>, keyed on the API key and pooled across every REST
 * endpoint (news, yield curve, …); only the trade WebSocket is exempt. So a single limiter,
 * injected into every REST client, is the correct control: no matter how many pollers exist,
 * their combined rate can't cross the cap. A sliding 60-second window with headroom under 60.
 *
 * <p>Non-blocking: {@link #tryAcquire()} returns false when the window is full, and pollers
 * simply skip that cycle (they retry next tick) rather than block a thread — fits the
 * fetch-and-cache pattern of both the news feed and the curve updater.
 */
public final class FinnhubRateLimiter {

    private static final long WINDOW_MILLIS = 60_000;

    private final int maxPerWindow;
    private final Deque<Long> callTimestamps = new ArrayDeque<>();

    /** @param maxPerMinute calls allowed per rolling minute; clamped to [1, 60] (leave headroom). */
    public FinnhubRateLimiter(int maxPerMinute) {
        this.maxPerWindow = Math.max(1, Math.min(60, maxPerMinute));
    }

    /** Reserves one call if the rolling-minute budget allows; false means "skip this cycle". */
    public synchronized boolean tryAcquire() {
        long now = System.currentTimeMillis();
        evictBefore(now - WINDOW_MILLIS);
        if (callTimestamps.size() >= maxPerWindow) {
            return false;
        }
        callTimestamps.addLast(now);
        return true;
    }

    /** Calls used in the current rolling window (observability/tests). */
    public synchronized int used() {
        evictBefore(System.currentTimeMillis() - WINDOW_MILLIS);
        return callTimestamps.size();
    }

    private void evictBefore(long cutoff) {
        while (!callTimestamps.isEmpty() && callTimestamps.peekFirst() <= cutoff) {
            callTimestamps.removeFirst();
        }
    }
}
