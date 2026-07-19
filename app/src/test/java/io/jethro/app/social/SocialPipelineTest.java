package io.jethro.app.social;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ADR-0050 adversarial controls: spam pre-filter, credibility, and the corroboration gate — a
 * single post or a low-credibility burst never promotes; a pump is flagged, never traded.
 */
class SocialPipelineTest {

    private static final Set<String> UNIVERSE = Set.of("AAPL", "MSFT", "NVDA");
    private static final Function<String, String> SECTOR = id -> "Information Technology";

    private static SocialChannels registry() {
        return new SocialChannels(Map.of(
                "wire:Reuters", SocialChannels.Tier.TRUSTED,
                "wire:Bloomberg", SocialChannels.Tier.TRUSTED,
                "st:Jane", SocialChannels.Tier.STANDARD),
                5_000, 180);
    }

    private static SocialPost credible(String channel, String text) {
        boolean standard = channel.startsWith("st:");
        return new SocialPost(java.util.UUID.randomUUID().toString(), "sim", channel, channel,
                standard ? 50_000 : 1, standard, standard ? 1000 : 1, 0L, text);
    }

    private static SocialPost throwaway(String channel, String text) {
        return new SocialPost(java.util.UUID.randomUUID().toString(), "sim", channel, "anon",
                5, false, 2, 0L, text); // unverified, few followers, new
    }

    @Test
    void cashtagsResolveOnlyToTheUniverse() {
        assertEquals(Set.of("AAPL", "MSFT"),
                Cashtags.extract("$AAPL breakout, watch $MSFT and $ZZZZ", UNIVERSE));
    }

    @Test
    void credibilityFollowsTierAndFloors() {
        SocialChannels ch = registry();
        assertTrue(ch.isCredible(credible("wire:Reuters", "$AAPL buy")), "trusted wire is credible");
        assertTrue(ch.isCredible(credible("st:Jane", "$AAPL buy")), "verified standard clears floors");
        assertFalse(ch.isCredible(throwaway("tg:pump0", "$AAPL moon")), "untrusted throwaway is not credible");
    }

    @Test
    void exactCopypastaIsDroppedAsDuplicate() {
        var posts = List.of(throwaway("tg:a", "$AAPL easy money buy"), throwaway("tg:b", "$AAPL easy money buy"));
        var r = new SpamFilter(3).filter(posts, new LinkedHashSet<>(), UNIVERSE);
        assertEquals(1, r.kept().size(), "second identical post is a duplicate");
        assertEquals(1, r.dropped().getOrDefault(SpamFilter.Drop.DUPLICATE, 0));
    }

    @Test
    void multiCashtagShillIsDroppedAsSpam() {
        // maxCashtags=2, post names 3 distinct universe tickers → over the limit → shill/spam drop.
        var r = new SpamFilter(2).filter(List.of(throwaway("tg:c", "hot list: $AAPL $MSFT $NVDA buy now")),
                new LinkedHashSet<>(), UNIVERSE);
        assertTrue(r.kept().isEmpty(), "a post shilling 3 tickers is spam");
        assertEquals(1, r.dropped().getOrDefault(SpamFilter.Drop.CASHTAG_SPAM, 0));
    }

    @Test
    void aSingleCredibleSourceDoesNotCorroborate() {
        var kept = List.of(credible("wire:Reuters", "$AAPL breakout buy"));
        var signals = CorroborationGate.evaluate(kept, UNIVERSE, SECTOR, registry(), 2, 4);
        assertTrue(signals.isEmpty(), "one credible channel is not enough (k=2)");
    }

    @Test
    void twoDistinctCredibleChannelsPromote() {
        var kept = List.of(
                credible("wire:Reuters", "$AAPL breakout buy"),
                credible("st:Jane", "$AAPL rally, long"));
        var signals = CorroborationGate.evaluate(kept, UNIVERSE, SECTOR, registry(), 2, 4);
        assertEquals(1, signals.size());
        var s = signals.get(0);
        assertEquals("AAPL", s.instrumentId());
        assertEquals("BULLISH", s.direction());
        assertFalse(s.manipulationSuspected(), "corroborated by credible channels — not a pump");
        assertEquals("Information Technology", s.sector());
    }

    @Test
    void aLowCredibilityBurstIsFlaggedAsManipulationNotPromoted() {
        List<SocialPost> kept = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            kept.add(throwaway("tg:pump" + i, "$NVDA to the moon buy now " + i)); // distinct text, distinct throwaways
        }
        var signals = CorroborationGate.evaluate(kept, UNIVERSE, SECTOR, registry(), 2, 4);
        assertEquals(1, signals.size());
        var s = signals.get(0);
        assertEquals("NVDA", s.instrumentId());
        assertTrue(s.manipulationSuspected(), "a low-credibility burst is a pump tell");
        assertEquals(0, s.corroboratingChannels(), "no credible channels behind it");
    }
}
