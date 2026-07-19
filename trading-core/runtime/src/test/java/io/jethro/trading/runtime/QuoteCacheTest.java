package io.jethro.trading.runtime;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The quote cache carries synthesized top-of-book sizes (ADR-0033) alongside bid/ask. */
class QuoteCacheTest {

    @Test
    void storesTouchSizes() {
        var cache = new QuoteCache();
        cache.update("ES", 5_450_000_000L, 5_450_100_000L, 5_000_000L, 6_000_000L, 123L);
        var q = cache.get("ES");
        assertEquals(5_000_000L, q.bidSizeScaled());
        assertEquals(6_000_000L, q.askSizeScaled());
        assertEquals(0, new BigDecimal("5").compareTo(q.bidSize()), "5,000,000 scaled 1e6 = 5 units");
        assertEquals(0, new BigDecimal("6").compareTo(q.askSize()));
    }

    @Test
    void sizelessUpdateLeavesDepthZero() {
        var cache = new QuoteCache();
        cache.update("ES", 100_000_000L, 100_100_000L, 1L); // the no-size overload
        assertEquals(0L, cache.get("ES").bidSizeScaled());
        assertEquals(0L, cache.get("ES").askSizeScaled());
    }
}
