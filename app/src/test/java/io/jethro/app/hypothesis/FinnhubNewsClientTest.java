package io.jethro.app.hypothesis;

import io.jethro.app.hypothesis.FinnhubNarrativeFeed.NewsSource.Article;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Finnhub news JSON parsing (ADR-0024): resilient to missing fields, never throws. */
class FinnhubNewsClientTest {

    @Test
    void parsesArticlesFromFinnhubArray() {
        String json = """
            [
              {"category":"company","datetime":1700000000,"headline":"Apple beats on earnings",
               "id":111,"related":"AAPL","source":"Reuters","summary":"Q4 tops estimates","url":"http://x"},
              {"category":"general","datetime":1700000500,"headline":"Fed holds rates steady",
               "id":222,"related":"","source":"AP","summary":"No change","url":"http://y"}
            ]
            """;
        List<Article> out = FinnhubNewsClient.parse(json);
        assertEquals(2, out.size());
        assertEquals(111, out.get(0).id());
        assertEquals(1700000000L, out.get(0).datetimeEpochSec());
        assertEquals("Apple beats on earnings", out.get(0).headline());
        assertEquals("Q4 tops estimates", out.get(0).summary());
        assertEquals("Fed holds rates steady", out.get(1).headline());
    }

    @Test
    void skipsBlankHeadlinesAndToleratesMissingFields() {
        String json = """
            [
              {"headline":"","id":1},
              {"headline":"Only a headline"}
            ]
            """;
        List<Article> out = FinnhubNewsClient.parse(json);
        assertEquals(1, out.size());
        assertEquals("Only a headline", out.get(0).headline());
        assertEquals(0, out.get(0).id());
        assertEquals(0, out.get(0).datetimeEpochSec());
    }

    @Test
    void nonArrayOrGarbageYieldsEmpty() {
        assertTrue(FinnhubNewsClient.parse("{\"error\":\"limit\"}").isEmpty());
        assertTrue(FinnhubNewsClient.parse("not json").isEmpty());
        assertTrue(FinnhubNewsClient.parse("[]").isEmpty());
    }
}
