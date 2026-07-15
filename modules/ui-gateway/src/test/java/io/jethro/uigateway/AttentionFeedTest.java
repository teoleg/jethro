package io.jethro.uigateway;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttentionFeedTest {

    private static AttentionFeed.AttentionItem item(String id, String title) {
        return new AttentionFeed.AttentionItem(id, System.currentTimeMillis(),
                AttentionFeed.Severity.INFO, "test", title, "body", "/x");
    }

    @Test
    void upsertByIdUpdatesInsteadOfDuplicating() {
        var feed = new AttentionFeed();
        feed.upsert(item("stale:AAPL", "first"));
        feed.upsert(item("stale:AAPL", "updated"));
        assertEquals(1, feed.snapshot().size());
        assertEquals("updated", feed.snapshot().get(0).title());
    }

    @Test
    void resolveRemovesClearedTrigger() {
        var feed = new AttentionFeed();
        feed.upsert(item("stale:AAPL", "stale"));
        feed.resolve("stale:AAPL");
        assertTrue(feed.snapshot().isEmpty());
    }

    @Test
    void newestFirstAndCapped() {
        var feed = new AttentionFeed();
        for (int i = 0; i < 150; i++) {
            feed.upsert(item("ai:" + i, "c" + i));
        }
        List<AttentionFeed.AttentionItem> snapshot = feed.snapshot();
        assertEquals(100, snapshot.size(), "feed is bounded");
        assertEquals("c149", snapshot.get(0).title(), "newest first");
    }

    @Test
    void staleRuleFiresAndResolves() {
        var feed = new AttentionFeed();
        var rules = new AttentionRules(feed, 10_000);
        long now = 1_000_000L;

        var stale = new MarkState.MarkDto("AAPL", "101.230000", null, null, "sim", now - 60_000, 60_000);
        rules.evaluate(List.of(stale), now);
        assertEquals(1, feed.snapshot().size());
        assertEquals(AttentionFeed.Severity.WARN, feed.snapshot().get(0).severity());
        assertTrue(feed.snapshot().get(0).body().contains("60s old"));

        var fresh = new MarkState.MarkDto("AAPL", "101.240000", null, null, "sim", now, 100);
        rules.evaluate(List.of(fresh), now);
        assertTrue(feed.snapshot().isEmpty(), "recovered mark resolves its alert");
    }
}
