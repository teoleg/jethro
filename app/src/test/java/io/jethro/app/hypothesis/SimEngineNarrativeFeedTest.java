package io.jethro.app.hypothesis;

import io.jethro.app.trading.TradingCoreLifecycle;
import io.jethro.trading.algo.hypothesis.NarrativeItem;
import io.jethro.trading.marketdata.sim.SimNewsEngine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Sim-generated news reaches the model as narrative items with the right sentiment and a display
 *  name (ADR-0034) — the headlines that moved the tape. */
class SimEngineNarrativeFeedTest {

    @Test
    void mapsSimNewsToNarrativeItemsWithSentimentAndName() {
        TradingCoreLifecycle core = mock(TradingCoreLifecycle.class);
        when(core.recentSimNews()).thenReturn(List.of(
                new SimNewsEngine.SimNewsEvent("sim-news-0", "ES", 1, "ES rallies on upbeat guidance", 5),
                new SimNewsEngine.SimNewsEvent("sim-news-1", "AAPL", -1, "AAPL slides on a guidance cut", 6)));
        InstrumentNameSource names = id -> "ES".equals(id) ? "S&P 500 e-mini" : null;

        var feed = new SimEngineNarrativeFeed(core, names);
        List<NarrativeItem> items = feed.poll("CALM", List.of(), 1_000L);

        assertEquals(2, items.size());
        assertEquals("ES", items.get(0).instrumentId());
        assertEquals(NarrativeItem.Sentiment.BULLISH, items.get(0).sentiment());
        assertTrue(items.get(0).headline().startsWith("S&P 500 e-mini"), "display name substituted");
        assertEquals("sim-news-0", items.get(0).id());

        assertEquals(NarrativeItem.Sentiment.BEARISH, items.get(1).sentiment());
        assertEquals("AAPL slides on a guidance cut", items.get(1).headline(), "no name → headline unchanged");
    }

    @Test
    void emptyWhenNoSimNews() {
        TradingCoreLifecycle core = mock(TradingCoreLifecycle.class);
        when(core.recentSimNews()).thenReturn(List.of());
        var feed = new SimEngineNarrativeFeed(core, InstrumentNameSource.NONE);
        assertTrue(feed.poll("CALM", List.of(), 1L).isEmpty());
    }
}
