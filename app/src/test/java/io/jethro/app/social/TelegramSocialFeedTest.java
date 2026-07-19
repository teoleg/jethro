package io.jethro.app.social;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Telegram getUpdates parse (ADR-0050 Phase 2b) — fixture-driven (no network): channel posts and
 * messages map to posts with {@code tg:<channel>}; empty-text updates are skipped; the update cursor
 * advances. A Telegram channel is credible only when curated TRUSTED (no follower signal on Telegram).
 */
class TelegramSocialFeedTest {

    private static final String FIXTURE = """
        {"ok":true,"result":[
          {"update_id":10,"channel_post":{"message_id":5,"date":1714564800,
            "chat":{"id":-100,"title":"Macro Desk","username":"macrodesk","type":"channel"},
            "text":"$AAPL breakout, desk long"}},
          {"update_id":11,"message":{"message_id":6,"date":1714564860,
            "chat":{"id":-101,"title":"Pumps","username":"pumpgroup","type":"supergroup"},
            "from":{"username":"shill99"},"text":"$NVDA moon buy now"}},
          {"update_id":12,"channel_post":{"message_id":7,"date":1714564900,
            "chat":{"username":"macrodesk"},"text":""}}
        ]}""";

    @Test
    void mapsChannelPostsAndMessagesSkippingEmpties() throws Exception {
        List<SocialPost> posts = TelegramSocialFeed.parse(FIXTURE, 0L);
        assertEquals(2, posts.size(), "empty-text update is skipped");
        assertEquals("tg:macrodesk", posts.get(0).channel());
        assertTrue(posts.get(0).text().contains("$AAPL"));
        assertEquals("tg:pumpgroup", posts.get(1).channel());
        assertEquals(1714564800L * 1000, posts.get(0).timestampMillis());
    }

    @Test
    void cursorAdvancesToTheHighestUpdateId() throws Exception {
        assertEquals(12L, TelegramSocialFeed.maxUpdateId(FIXTURE));
    }

    @Test
    void aCuratedTrustedChannelIsCredibleAnUncuratedOneIsNot() throws Exception {
        SocialChannels ch = new SocialChannels(
                java.util.Map.of("tg:macrodesk", SocialChannels.Tier.TRUSTED),
                SocialChannels.Tier.UNTRUSTED, 5_000, 180);
        List<SocialPost> posts = TelegramSocialFeed.parse(FIXTURE, 0L);
        assertTrue(ch.isCredible(posts.get(0)), "curated trusted Telegram channel is credible");
        org.junit.jupiter.api.Assertions.assertFalse(ch.isCredible(posts.get(1)),
                "uncurated Telegram group is not credible (no follower signal)");
    }
}
