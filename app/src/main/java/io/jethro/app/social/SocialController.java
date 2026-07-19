package io.jethro.app.social;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only view of the ADR-0050 social pipeline so the adversarial controls are legible: the ingest
 * counters (kept vs shed), the corroborated advisory subjects with their sector, any suspected-pump
 * flags, and a sample of recent kept posts with their channel tier and credibility. Advisory context
 * only — nothing here is or becomes an order (ADR-0049).
 */
@RestController
public final class SocialController {

    private final ObjectProvider<SocialLifecycle> lifecycle;

    public SocialController(ObjectProvider<SocialLifecycle> lifecycle) {
        this.lifecycle = lifecycle;
    }

    public record Counters(long ingested, long kept, long duplicateDropped, long cashtagSpamDropped,
                           long corroborated, long manipulationSuspected) {
    }

    public record SignalView(String instrument, String sector, String direction, int channels,
                             int mentions, boolean manipulationSuspected, String sample) {
    }

    public record PostView(long timestampMillis, String channel, String tier, boolean credible, String text) {
    }

    public record SocialView(boolean available, long lastRunMillis, Counters counters,
                             List<SignalView> signals, List<PostView> recent) {
    }

    @GetMapping("/api/social")
    public SocialView social() {
        SocialLifecycle s = lifecycle.getIfAvailable();
        if (s == null) {
            return new SocialView(false, 0, new Counters(0, 0, 0, 0, 0, 0), List.of(), List.of());
        }
        long[] c = s.counters();
        List<SignalView> signals = s.signals().stream()
                .map(x -> new SignalView(x.instrumentId(), x.sector(), x.direction(), x.corroboratingChannels(),
                        x.mentions(), x.manipulationSuspected(), x.sample()))
                .toList();
        List<PostView> recent = s.recentPosts().stream()
                .map(p -> new PostView(p.timestampMillis(), p.channel(), s.tierOf(p.channel()),
                        s.credible(p), p.text()))
                .toList();
        return new SocialView(true, s.lastRunMillis(),
                new Counters(c[0], c[1], c[2], c[3], c[4], c[5]), signals, recent);
    }
}
