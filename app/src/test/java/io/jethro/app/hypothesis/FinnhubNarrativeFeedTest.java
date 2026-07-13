package io.jethro.app.hypothesis;

import io.jethro.app.hypothesis.FinnhubNarrativeFeed.NewsSource;
import io.jethro.app.hypothesis.FinnhubNarrativeFeed.NewsSource.Article;
import io.jethro.trading.algo.hypothesis.NarrativeItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-news narrative feed (ADR-0024): maps Finnhub articles to {@link NarrativeItem} keyed on
 * the INTERNAL instrumentId (invariant 2 — the Finnhub symbol only queries, never surfaces),
 * dedupes by article id, throttles refreshes, and derives a coarse (never numeric) sentiment.
 */
class FinnhubNarrativeFeedTest {

    /** Fake source: canned articles + call counters, so we assert fetch throttling without a network. */
    private static final class FakeNews implements NewsSource {
        int companyCalls;
        int generalCalls;
        Map<String, List<Article>> bySymbol = Map.of();
        List<Article> general = List.of();

        @Override
        public List<Article> companyNews(String finnhubSymbol) {
            companyCalls++;
            return bySymbol.getOrDefault(finnhubSymbol, List.of());
        }

        @Override
        public List<Article> generalNews() {
            generalCalls++;
            return general;
        }
    }

    // GOOG (internal) maps to GOOGL (Finnhub) — proves the symbol never leaks into the item.
    private static final Map<String, String> SYMBOLS = Map.of("AAPL", "AAPL", "GOOG", "GOOGL");

    @Test
    void companyNewsKeysOnInternalIdNotFinnhubSymbol() {
        var news = new FakeNews();
        news.bySymbol = Map.of("GOOGL", List.of(
                new Article(500, 1_700_000_300L, "Alphabet beats on earnings", "revenue tops")));
        var feed = new FinnhubNarrativeFeed(news, SYMBOLS, 60_000);

        List<NarrativeItem> out = feed.poll("CALM", List.of("GOOG"), 0);
        assertEquals(1, out.size());
        NarrativeItem item = out.get(0);
        assertEquals("GOOG", item.instrumentId(), "internal id, never the Finnhub symbol GOOGL");
        assertFalse(item.headline().isEmpty());
        assertEquals(NarrativeItem.Category.EARNINGS, item.category(), "'earnings'/'revenue' → EARNINGS");
        assertEquals(NarrativeItem.Sentiment.BULLISH, item.sentiment(), "'beats'/'tops' → bullish");
    }

    @Test
    void generalNewsIsMacroWithNoInstrument() {
        var news = new FakeNews();
        news.general = List.of(new Article(900, 1_700_000_000L, "Fed holds rates steady", "no change"));
        var feed = new FinnhubNarrativeFeed(news, SYMBOLS, 60_000);

        List<NarrativeItem> out = feed.poll("CALM", List.of(), 0);
        assertEquals(1, out.size());
        assertNull(out.get(0).instrumentId(), "macro item has no instrument");
        assertEquals(NarrativeItem.Category.MACRO, out.get(0).category());
    }

    @Test
    void dedupesByArticleIdAcrossRefreshes() {
        var news = new FakeNews();
        news.general = List.of(new Article(42, 1_700_000_000L, "Same macro headline", ""));
        var feed = new FinnhubNarrativeFeed(news, SYMBOLS, 60_000);

        feed.poll("CALM", List.of(), 0);
        List<NarrativeItem> out = feed.poll("CALM", List.of(), 61_000); // past throttle → refetches, same id
        assertEquals(1, out.size(), "the repeated article surfaces once");
        assertEquals(2, news.generalCalls, "refetched after the throttle window");
    }

    @Test
    void throttlesRefreshWithinTheWindow() {
        var news = new FakeNews();
        news.general = List.of(new Article(1, 1_700_000_000L, "Headline", ""));
        var feed = new FinnhubNarrativeFeed(news, SYMBOLS, 60_000);

        feed.poll("CALM", List.of(), 0);       // fetch #1
        feed.poll("CALM", List.of(), 1_000);   // within window → no fetch
        feed.poll("CALM", List.of(), 30_000);  // still within window → no fetch
        assertEquals(1, news.generalCalls, "one fetch inside the throttle window");
        feed.poll("CALM", List.of(), 61_000);  // window elapsed → fetch #2
        assertEquals(2, news.generalCalls);
    }

    @Test
    void unmappedSingleNamesFetchNoCompanyNews() {
        var news = new FakeNews();
        var feed = new FinnhubNarrativeFeed(news, SYMBOLS, 60_000);
        feed.poll("CALM", List.of("ZN", "USD_IRS_5Y"), 0); // neither is Finnhub-covered
        assertEquals(0, news.companyCalls, "no company-news calls for unmapped names");
        assertEquals(1, news.generalCalls, "general news still pulled");
    }

    @Test
    void bearishAndNeutralSentimentHeuristics() {
        var news = new FakeNews();
        news.bySymbol = Map.of(
                "AAPL", List.of(new Article(1, 1_700_000_100L, "Apple plunges after profit warning", "probe")),
                "GOOGL", List.of(new Article(2, 1_700_000_200L, "Alphabet trades sideways in quiet session", "")));
        var feed = new FinnhubNarrativeFeed(news, SYMBOLS, 60_000);

        List<NarrativeItem> out = feed.poll("CALM", List.of("AAPL", "GOOG"), 0);
        NarrativeItem apple = out.stream().filter(i -> "AAPL".equals(i.instrumentId())).findFirst().orElseThrow();
        NarrativeItem goog = out.stream().filter(i -> "GOOG".equals(i.instrumentId())).findFirst().orElseThrow();
        assertEquals(NarrativeItem.Sentiment.BEARISH, apple.sentiment(), "'plunges'/'warning'/'probe' → bearish");
        assertEquals(NarrativeItem.Sentiment.NEUTRAL, goog.sentiment(), "no signal words → neutral");
    }

    @Test
    void returnsNewestLast() {
        var news = new FakeNews();
        news.general = List.of(new Article(1, 100L, "older", ""));
        news.bySymbol = Map.of("AAPL", List.of(new Article(2, 300L, "newer", "")));
        var feed = new FinnhubNarrativeFeed(news, SYMBOLS, 60_000);

        List<NarrativeItem> out = feed.poll("CALM", List.of("AAPL"), 0);
        assertEquals(2, out.size());
        assertTrue(out.get(0).timestampMillis() <= out.get(out.size() - 1).timestampMillis(),
                "sorted ascending — newest last, matching the sim feed");
        assertEquals("newer", out.get(out.size() - 1).headline());
    }
}
