package io.jethro.app.social;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * StockTwits parse (ADR-0050 Phase 2) — fixture-driven (no network): the response maps to posts
 * carrying the author credibility signals the corroboration gate needs, and missing fields degrade
 * safely. The live HTTP call is best-effort/fail-open and gated off by default, so this locks the
 * mapping, which is the part we can deterministically verify.
 */
class StockTwitsSocialFeedTest {

    private static final String FIXTURE = """
        {"messages":[
          {"id":101,"body":"$AAPL breakout, adding here","created_at":"2024-05-01T12:00:00Z",
           "user":{"username":"BigDeskJane","followers":52000,"official":true,"join_date":"2016-03-01"}},
          {"id":102,"body":"$NVDA to the moon buy now","created_at":"2024-05-01T12:01:00Z",
           "user":{"username":"anon123","followers":4,"official":false,"join_date":"2024-04-20"}},
          {"id":103,"body":"no join date here $MSFT"}
        ]}""";

    @Test
    void mapsMessagesToPostsWithCredibilitySignals() throws Exception {
        List<SocialPost> posts = StockTwitsSocialFeed.parse(FIXTURE, 1_700_000_000_000L);
        assertEquals(3, posts.size());

        SocialPost jane = posts.get(0);
        assertEquals("stocktwits:BigDeskJane", jane.channel());
        assertEquals(52000, jane.followers());
        assertTrue(jane.verified(), "official → verified");
        assertTrue(jane.accountAgeDays() > 2000, "2016 join date is a seasoned account");
        assertTrue(jane.text().contains("$AAPL"));

        SocialPost anon = posts.get(1);
        assertFalse(anon.verified());
        assertEquals(4, anon.followers());
        assertTrue(anon.accountAgeDays() >= 0);

        SocialPost noDate = posts.get(2);
        assertEquals(0, noDate.accountAgeDays(), "missing join date → treated as brand-new (0)");
        assertEquals(1_700_000_000_000L, noDate.timestampMillis(), "missing created_at → fallback now");
    }

    @Test
    void anOrganicStockTwitsUserIsFloorEvaluatedUnderStandardDefault() throws Exception {
        // A real feed sets default-tier=STANDARD so unknown accounts are judged by the floors:
        // the seasoned verified user is credible, the throwaway is not — the pump defence still holds.
        SocialChannels ch = new SocialChannels(java.util.Map.of(), SocialChannels.Tier.STANDARD, 5_000, 180);
        List<SocialPost> posts = StockTwitsSocialFeed.parse(FIXTURE, 1_700_000_000_000L);
        assertTrue(ch.isCredible(posts.get(0)), "verified, 52k followers, seasoned → credible");
        assertFalse(ch.isCredible(posts.get(1)), "unverified, 4 followers, brand-new → not credible");
    }
}
