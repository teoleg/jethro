package io.jethro.app.discovery;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only view of the universe-discovery register (ADR-0050 §7): candidate additions to the tracked
 * list — untracked instruments credible news/social are talking about, ranked, with which sources
 * name them. Suggestions only; promoting one to the base list (and to reference data) is a human/later
 * decision, never automatic.
 */
@RestController
public final class DiscoveryController {

    private final UniverseCandidates candidates;
    private final ObjectProvider<DiscoveryLifecycle> lifecycle;

    public DiscoveryController(UniverseCandidates candidates, ObjectProvider<DiscoveryLifecycle> lifecycle) {
        this.candidates = candidates;
        this.lifecycle = lifecycle;
    }

    public record CandidateView(String instrument, double score, int mentions, List<String> sources,
                                long firstSeenMillis, long lastSeenMillis, String sample) {
    }

    public record NewsStatusView(String name, boolean healthy, long lastPollMillis, int lastCount, String detail) {
    }

    public record DiscoveryView(boolean available, NewsStatusView news, List<CandidateView> candidates) {
    }

    @GetMapping("/api/discovery")
    public DiscoveryView discovery() {
        List<CandidateView> rows = candidates.ranked(50).stream()
                .map(c -> new CandidateView(c.instrumentId(), c.score(), c.mentions(),
                        c.sources().stream().sorted().toList(), c.firstSeenMillis(), c.lastSeenMillis(), c.sample()))
                .toList();
        DiscoveryLifecycle live = lifecycle.getIfAvailable();
        NewsStatusView news = null;
        if (live != null) {
            var s = live.newsStatus();
            news = new NewsStatusView(s.name(), s.healthy(), s.lastPollMillis(), s.lastCount(), s.detail());
        }
        return new DiscoveryView(true, news, rows);
    }
}
