package io.jethro.app.hypothesis;

import io.jethro.trading.algo.hypothesis.NarrativeItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The narrative sim feed is deterministic and regime-correlated (ADR-0022 / ADR-0009). */
class SimNarrativeFeedTest {

    private static final List<String> NAMES = List.of("AAPL", "MSFT", "ES");

    @Test
    void sameSeedAndRegimeSequenceProduceIdenticalHeadlines() {
        var a = new SimNarrativeFeed(7);
        var b = new SimNarrativeFeed(7);
        String[] regimes = {"CALM", "TREND_UP", "TREND_UP", "VOLATILE", "TREND_DOWN", "CALM"};
        List<NarrativeItem> lastA = List.of();
        List<NarrativeItem> lastB = List.of();
        for (int i = 0; i < regimes.length; i++) {
            lastA = a.poll(regimes[i], NAMES, 1000 + i);
            lastB = b.poll(regimes[i], NAMES, 1000 + i);
        }
        assertEquals(lastA.size(), lastB.size());
        for (int i = 0; i < lastA.size(); i++) {
            assertEquals(lastA.get(i).id(), lastB.get(i).id());
            assertEquals(lastA.get(i).headline(), lastB.get(i).headline());
            assertEquals(lastA.get(i).sentiment(), lastB.get(i).sentiment());
        }
    }

    @Test
    void trendUpPrintsOnlyBullishItems() {
        var feed = new SimNarrativeFeed(3);
        for (int i = 0; i < 40; i++) {
            for (NarrativeItem item : feed.poll("TREND_UP", NAMES, i)) {
                assertEquals(NarrativeItem.Sentiment.BULLISH, item.sentiment(), item.headline());
            }
        }
    }

    @Test
    void calmPrintsOnlyNeutralMacro() {
        var feed = new SimNarrativeFeed(5);
        boolean sawAny = false;
        for (int i = 0; i < 60; i++) {
            for (NarrativeItem item : feed.poll("CALM", NAMES, i)) {
                sawAny = true;
                assertEquals(NarrativeItem.Sentiment.NEUTRAL, item.sentiment());
                assertEquals(NarrativeItem.Category.MACRO, item.category());
            }
        }
        assertTrue(sawAny, "CALM should still emit some items over 60 polls");
    }
}
