package io.jethro.app.social;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * News merged into the ADR-0050 pipeline: outlets are credible channels, so a subject named by two
 * outlets — or by an outlet AND a social account — cross-corroborates into one advisory signal.
 * News tiering: official (fed/sec/…) TRUSTED, media (cnbc/yahoo) STANDARD (credible via the floors).
 */
class SocialNewsMergeTest {

    // Official=TRUSTED, media=STANDARD; STANDARD is judged by the author floors (5k followers, 180d).
    private static final SocialChannels CHANNELS = new SocialChannels(
            Map.of("fed", SocialChannels.Tier.TRUSTED, "sec", SocialChannels.Tier.TRUSTED,
                    "cnbc", SocialChannels.Tier.STANDARD, "yahoo", SocialChannels.Tier.STANDARD,
                    "st:AnalystJane", SocialChannels.Tier.STANDARD),
            SocialChannels.Tier.UNTRUSTED, 5_000, 180);

    /** A credible news post as {@link NewsSocialFeed} builds them (high synthetic author signals). */
    private static SocialPost news(String outlet, String text) {
        return new SocialPost("news-" + outlet + "-" + text.hashCode(), "news", outlet, outlet,
                5_000_000, true, 3650, System.currentTimeMillis(), text);
    }

    private static List<SocialSignal> gate(List<SocialPost> posts) {
        return CorroborationGate.evaluate(posts, Set.of(), id -> "untracked", CHANNELS, 2, 4);
    }

    @Test
    void twoOutletsNamingTheSameTickerCorroborate() {
        var signals = gate(List.of(
                news("cnbc", "Tesla surges on delivery beat $TSLA"),
                news("yahoo", "Tesla extends rally after upgrade $TSLA")));
        var tsla = signals.stream().filter(s -> s.instrumentId().equals("TSLA")).findFirst().orElseThrow();
        assertTrue(tsla.corroboratingChannels() >= 2);
        assertFalse(tsla.manipulationSuspected(), "two credible outlets is corroboration, not a pump");
    }

    @Test
    void newsAndSocialCrossCorroborate() {
        SocialPost social = new SocialPost("st-1", "stocktwits", "st:AnalystJane", "AnalystJane",
                40_000, true, 900, System.currentTimeMillis(), "watching $NVDA into earnings");
        var signals = gate(List.of(news("sec", "SEC notes filing by Nvidia $NVDA"), social));
        var nvda = signals.stream().filter(s -> s.instrumentId().equals("NVDA")).findFirst().orElseThrow();
        assertTrue(nvda.corroboratingChannels() >= 2, "an outlet + a credible social account corroborate");
        assertFalse(nvda.manipulationSuspected());
    }

    @Test
    void aSingleOutletDoesNotCorroborate() {
        var signals = gate(List.of(news("cnbc", "Some single mention $AMD")));
        boolean promoted = signals.stream()
                .anyMatch(s -> s.instrumentId().equals("AMD") && !s.manipulationSuspected() && s.corroboratingChannels() >= 2);
        assertFalse(promoted, "one channel is below the k=2 corroboration bar");
    }

    @Test
    void officialOutletIsCredibleUnconditionally() {
        assertTrue(CHANNELS.isCredible(news("fed", "policy statement")));
        assertEquals(SocialChannels.Tier.TRUSTED, CHANNELS.tierOf("sec"));
        assertEquals(SocialChannels.Tier.STANDARD, CHANNELS.tierOf("cnbc"));
    }
}
