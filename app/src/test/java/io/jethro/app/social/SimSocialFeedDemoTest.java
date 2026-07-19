package io.jethro.app.social;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end demo check (ADR-0050): one poll of the seedable sim feed, run through the real spam
 * filter + corroboration gate with the shipped defaults, produces BOTH a corroborated advisory
 * subject and a flagged pump — i.e. the panel shows results in sim, and the adversarial controls fire.
 */
class SimSocialFeedDemoTest {

    @Test
    void oneSimCycleProducesACorroboratedSubjectAndAFlaggedPump() {
        List<String> universe = List.of("AAPL", "MSFT", "NVDA", "GOOG", "JNJ", "JPM");
        Set<String> universeSet = new LinkedHashSet<>(universe);
        SocialChannels channels = new SocialChannels(Map.of(
                "wire:Reuters", SocialChannels.Tier.TRUSTED,
                "wire:Bloomberg", SocialChannels.Tier.TRUSTED,
                "st:AnalystJane", SocialChannels.Tier.STANDARD,
                "st:MacroMike", SocialChannels.Tier.STANDARD), 5_000, 180);

        var posts = new SimSocialFeed(42L).poll(universe, System.currentTimeMillis());
        var kept = new SpamFilter(3).filter(posts, new LinkedHashSet<>());
        var signals = CorroborationGate.evaluate(kept.kept(), universeSet,
                id -> "Information Technology", channels, 2, 4);

        assertTrue(kept.dropped().getOrDefault(SpamFilter.Drop.DUPLICATE, 0) >= 1
                        || kept.dropped().getOrDefault(SpamFilter.Drop.CASHTAG_SPAM, 0) >= 1,
                "the sim emits spam that the filter sheds");
        assertTrue(signals.stream().anyMatch(s -> !s.manipulationSuspected()),
                "at least one corroborated advisory subject");
        assertTrue(signals.stream().anyMatch(SocialSignal::manipulationSuspected),
                "the synthetic pump is flagged, not promoted");
    }
}
