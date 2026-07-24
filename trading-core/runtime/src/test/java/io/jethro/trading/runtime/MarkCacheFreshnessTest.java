package io.jethro.trading.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Multi-source freshness guard (ADR-0056): with more than one source on an instrument (a real-time
 * Finnhub WS + a delayed Yahoo fallback), a laggard mark must not regress a fresher one backwards in
 * time. The guard compares PROVIDER timestamps; a stale/warm or first mark is exempt so a fallback
 * still fills a genuine gap (the property that keeps a single-source hiccup from blanking exposure).
 * Exact scaled-long assertions.
 */
class MarkCacheFreshnessTest {

    @Test
    void olderProviderTimestampDoesNotRegressAFresherMark() {
        var cache = new MarkCache();
        cache.update("AAPL", 324_580_000L, 1_000L, 1_000L, "finnhub"); // live @ provider ts 1000
        cache.update("AAPL", 322_000_000L, 500L, 2_000L, "yahoo");     // delayed: older provider ts, later ingest
        var h = cache.get("AAPL");
        assertEquals(324_580_000L, h.priceScaled(), "delayed Yahoo mark must not overwrite fresher Finnhub");
        assertEquals("finnhub", h.source());
        assertEquals(1, cache.supersededTicks());
    }

    @Test
    void newerProviderTimestampIsAccepted() {
        var cache = new MarkCache();
        cache.update("AAPL", 324_580_000L, 1_000L, 1_000L, "finnhub");
        cache.update("AAPL", 325_100_000L, 2_000L, 2_000L, "yahoo");   // newer provider ts wins
        var h = cache.get("AAPL");
        assertEquals(325_100_000L, h.priceScaled());
        assertEquals("yahoo", h.source());
        assertEquals(0, cache.supersededTicks());
    }

    @Test
    void firstMarkIsAcceptedRegardlessOfTimestamp() {
        var cache = new MarkCache();
        cache.update("JPM", 344_000_000L, 5L, 5L, "yahoo"); // no prior mark → accepted even at a low ts
        assertEquals(344_000_000L, cache.get("JPM").priceScaled());
        assertEquals(0, cache.supersededTicks());
    }

    @Test
    void warmStaleMarkIsExemptSoAFallbackCanFillTheGap() {
        var cache = new MarkCache();
        cache.loadStale("JPM", 350_000_000L, 9_000L, "warm"); // stale holder at a HIGH provider ts
        cache.update("JPM", 344_000_000L, 1_000L, 1_000L, "yahoo"); // older ts, but holder is stale → accepted
        var h = cache.get("JPM");
        assertEquals(344_000_000L, h.priceScaled(), "a stale/warm mark is exempt so a live fallback refreshes it");
        assertFalse(h.stale());
        assertEquals("yahoo", h.source());
        assertEquals(0, cache.supersededTicks());
    }
}
