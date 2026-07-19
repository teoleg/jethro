package io.jethro.app.discovery;

import io.jethro.app.social.Cashtags;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RSS parse (ADR-0045/0050) — fixture-driven (no network): items map to NewsItems (title+desc), a
 *  bad pubDate falls back, and cashtag extraction over the text finds the discussed tickers. XXE-safe. */
class RssNewsFeedTest {

    private static final String RSS = """
        <?xml version="1.0"?>
        <rss version="2.0"><channel><title>Wire</title>
          <item>
            <title>$ARM soars on AI demand</title>
            <description>Arm Holdings raised guidance; peers $NVDA also up.</description>
            <link>https://example.com/a</link>
            <pubDate>Wed, 01 May 2024 12:00:00 GMT</pubDate>
          </item>
          <item>
            <title>Fed holds rates</title>
            <description>No cashtags here.</description>
            <link>https://example.com/b</link>
            <pubDate>not-a-date</pubDate>
          </item>
        </channel></rss>""";

    @Test
    void parsesItemsWithTitleDescriptionAndFallbackDate() throws Exception {
        List<NewsItem> items = RssNewsFeed.parse(RSS, "wire", 999L);
        assertEquals(2, items.size());
        assertEquals("wire", items.get(0).outlet());
        assertTrue(items.get(0).text().contains("$ARM") && items.get(0).text().contains("$NVDA"));
        assertEquals(1714564800000L, items.get(0).timestampMillis(), "RFC-1123 pubDate parsed");
        assertEquals(999L, items.get(1).timestampMillis(), "bad pubDate falls back to now");
    }

    @Test
    void cashtagsOverNewsTextFindTheDiscussedTickers() throws Exception {
        var item = RssNewsFeed.parse(RSS, "wire", 0L).get(0);
        assertEquals(Set.of("ARM", "NVDA"), Cashtags.extractCashtags(item.text()));
    }
}
