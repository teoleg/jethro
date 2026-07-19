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
    private final SocialProperties props;

    public SocialController(ObjectProvider<SocialLifecycle> lifecycle, SocialProperties props) {
        this.lifecycle = lifecycle;
        this.props = props;
    }

    public record Counters(long ingested, long kept, long duplicateDropped, long cashtagSpamDropped,
                           long corroborated, long manipulationSuspected) {
    }

    public record SignalView(String instrument, boolean tracked, String sector, String direction,
                             int channels, int mentions, boolean manipulationSuspected, String sample) {
    }

    public record PostView(long timestampMillis, String channel, String tier, boolean credible, String text) {
    }

    /** A source's live connection health (ADR-0050) — so the site connections are VISIBLE. */
    public record SourceView(String name, boolean healthy, long lastPollMillis, int lastCount, String detail) {
    }

    /** The active controls, surfaced read-only so the running policy is legible. */
    public record Controls(List<String> sources, long intervalSeconds, int corroborationChannels,
                           int burstThreshold, int maxCashtags, int credibleFollowerFloor,
                           int credibleAgeDaysFloor, String defaultTier) {
    }

    public record SocialView(boolean available, long lastRunMillis, Counters counters,
                             List<SourceView> sources, Controls controls,
                             List<SignalView> signals, List<PostView> recent) {
    }

    @GetMapping("/api/social")
    public SocialView social() {
        Controls controls = new Controls(props.sourcesOrDefault(), props.intervalSecondsOrDefault(),
                props.kOrDefault(), props.burstThresholdOrDefault(), props.maxCashtagsOrDefault(),
                props.followerFloorOrDefault(), props.ageFloorOrDefault(), props.defaultTierOrDefault().name());
        SocialLifecycle s = lifecycle.getIfAvailable();
        if (s == null) {
            return new SocialView(false, 0, new Counters(0, 0, 0, 0, 0, 0), List.of(), controls, List.of(), List.of());
        }
        long[] c = s.counters();
        List<SourceView> sources = s.sourceHealth().stream()
                .map(h -> new SourceView(h.name(), h.healthy(), h.lastPollMillis(), h.lastCount(), h.detail()))
                .toList();
        List<SignalView> signals = s.signals().stream()
                .map(x -> new SignalView(x.instrumentId(), x.tracked(), x.sector(), x.direction(),
                        x.corroboratingChannels(), x.mentions(), x.manipulationSuspected(), x.sample()))
                .toList();
        List<PostView> recent = s.recentPosts().stream()
                .map(p -> new PostView(p.timestampMillis(), p.channel(), s.tierOf(p.channel()),
                        s.credible(p), p.text()))
                .toList();
        return new SocialView(true, s.lastRunMillis(),
                new Counters(c[0], c[1], c[2], c[3], c[4], c[5]), sources, controls, signals, recent);
    }
}
