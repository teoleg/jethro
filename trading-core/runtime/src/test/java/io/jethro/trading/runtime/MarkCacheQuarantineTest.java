package io.jethro.trading.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Corporate-action / bad-print guard: a jump past the threshold quarantines the instrument
 * (the suspect price never enters the cache), further ticks are rejected-and-counted, and
 * an operator clear accepts the next mark as the new baseline. Warm/stale marks are exempt
 * on their first refresh. Scaled-long arithmetic — exact assertions.
 */
class MarkCacheQuarantineTest {

    private static final MarkCache.JumpThresholds EQUITY_20PCT = id -> 2000;

    @Test
    void jumpPastThresholdQuarantinesAndFreezesTheLastGoodMark() {
        var cache = new MarkCache(EQUITY_20PCT);
        cache.update("AAPL", 190_000_000L, 1, 1, "sim");   // 190.00
        cache.update("AAPL", 193_000_000L, 2, 2, "sim");   // +1.6% — normal, accepted

        cache.update("AAPL", 96_500_000L, 3, 3, "sim");    // −50% — a 2:1 split, not a crash
        var holder = cache.get("AAPL");
        assertTrue(holder.quarantined());
        assertEquals(193_000_000L, holder.priceScaled(), "last GOOD mark stays — suspect never applied");
        assertEquals(1, cache.rejectedTicks());

        cache.update("AAPL", 96_600_000L, 4, 4, "sim");    // still the new level — still rejected
        assertEquals(193_000_000L, holder.priceScaled());
        assertEquals(2, cache.rejectedTicks(), "every rejected tick is counted, never silent");

        var quarantined = cache.quarantined();
        assertEquals(1, quarantined.size());
        assertEquals("AAPL", quarantined.get(0).instrumentId());
        assertEquals(0, quarantined.get(0).lastGoodPrice().compareTo(new java.math.BigDecimal("193")));
        assertEquals(0, quarantined.get(0).suspectPrice().compareTo(new java.math.BigDecimal("96.6")));
    }

    @Test
    void operatorClearAcceptsTheNextMarkAsTheNewBaseline() {
        var cache = new MarkCache(EQUITY_20PCT);
        cache.update("AAPL", 190_000_000L, 1, 1, "sim");
        cache.update("AAPL", 95_000_000L, 2, 2, "sim");    // split → quarantined
        assertTrue(cache.get("AAPL").quarantined());

        assertTrue(cache.clearQuarantine("AAPL"));
        cache.update("AAPL", 95_100_000L, 3, 3, "sim");    // post-split level accepted
        assertFalse(cache.get("AAPL").quarantined());
        assertEquals(95_100_000L, cache.get("AAPL").priceScaled());

        cache.update("AAPL", 96_000_000L, 4, 4, "sim");    // and the guard re-arms off the new base
        assertEquals(96_000_000L, cache.get("AAPL").priceScaled());
        assertFalse(cache.clearQuarantine("AAPL"), "clearing a non-quarantined instrument reports false");
    }

    @Test
    void firstRefreshAfterWarmLoadIsExempt() {
        var cache = new MarkCache(EQUITY_20PCT);
        cache.loadStale("AAPL", 190_000_000L, 1, "warm");
        // Market moved 30% while we were down — legitimate, not a corporate action.
        cache.update("AAPL", 247_000_000L, 2, 2, "sim");
        assertFalse(cache.get("AAPL").quarantined());
        assertEquals(247_000_000L, cache.get("AAPL").priceScaled());
    }

    @Test
    void disabledThresholdNeverQuarantines() {
        var cache = new MarkCache(); // JumpThresholds.DISABLED
        cache.update("AAPL", 190_000_000L, 1, 1, "sim");
        cache.update("AAPL", 19_000_000L, 2, 2, "sim");
        assertFalse(cache.get("AAPL").quarantined());
        assertEquals(19_000_000L, cache.get("AAPL").priceScaled());
    }
}
