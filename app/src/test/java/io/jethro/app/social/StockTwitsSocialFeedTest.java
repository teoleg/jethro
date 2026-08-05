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

    /**
     * ADR-0139: the live endpoint returns {@code official:false} for every organic author — the flag
     * marks StockTwits' OWN accounts — while {@code followers}/{@code join_date} are populated. Under
     * the old conjunction that short-circuited before the floors were read, so no organic post could
     * ever corroborate. The floors must be what decides.
     */
    private static String organicFixture() {
        // The age floor is measured against TODAY, so the brand-new account's join date must be
        // relative — a literal date would silently season past the floor as the clock advances.
        String tenDaysAgo = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(10).toString();
        return """
            {"messages":[
              {"id":201,"body":"$AAPL holding through earnings","created_at":"2024-05-01T12:00:00Z",
               "user":{"username":"seasoned","followers":5978,"official":false,"join_date":"2018-06-14"}},
              {"id":202,"body":"$AAPL squeeze incoming","created_at":"2024-05-01T12:01:00Z",
               "user":{"username":"bigbutnew","followers":50000,"official":false,"join_date":"%s"}},
              {"id":203,"body":"$AAPL rally","created_at":"2024-05-01T12:02:00Z",
               "user":{"username":"oldbutsmall","followers":204,"official":false,"join_date":"2018-06-14"}}
            ]}""".formatted(tenDaysAgo);
    }

    @Test
    void anUnverifiedOrganicAuthorIsJudgedByTheFloorsAndBothStillBind() throws Exception {
        SocialChannels ch = new SocialChannels(java.util.Map.of(), SocialChannels.Tier.STANDARD, 5_000, 180);
        List<SocialPost> posts = StockTwitsSocialFeed.parse(organicFixture(), 1_700_000_000_000L);

        assertFalse(posts.get(0).verified(), "the live shape: official is false for organic authors");
        assertTrue(ch.isCredible(posts.get(0)), "unverified but 5,978 followers and seasoned → credible");

        // Each floor still binds on its own — the disjunction is with verification, not between floors.
        assertFalse(ch.isCredible(posts.get(1)), "50k followers but brand-new → still not credible");
        assertFalse(ch.isCredible(posts.get(2)), "seasoned but 204 followers → still not credible");
    }
}
