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
                                long firstSeenMillis, long lastSeenMillis, int distinctDays, String sample) {
    }

    public record NewsStatusView(String name, boolean healthy, long lastPollMillis, int lastCount, String detail) {
    }

    /** One recent headline as a financial-data source: outlet, time, title (link), and the instruments
     *  it mentions — {@code untracked} being the subset that are discovery candidates. */
    public record HeadlineView(long atMillis, String outlet, String title, String url,
                               List<String> tickers, List<String> untracked) {
    }

    public record DiscoveryView(boolean available, NewsStatusView news, List<CandidateView> candidates,
                                List<HeadlineView> headlines) {
    }

    @GetMapping("/api/discovery")
    public DiscoveryView discovery() {
        List<CandidateView> rows = candidates.ranked(50).stream()
                .map(c -> new CandidateView(c.instrumentId(), c.score(), c.mentions(),
                        c.sources().stream().sorted().toList(), c.firstSeenMillis(), c.lastSeenMillis(),
                        c.distinctDays(), c.sample()))
                .toList();
        DiscoveryLifecycle live = lifecycle.getIfAvailable();
        NewsStatusView news = null;
        List<HeadlineView> headlines = List.of();
        if (live != null) {
            var s = live.newsStatus();
            news = new NewsStatusView(s.name(), s.healthy(), s.lastPollMillis(), s.lastCount(), s.detail());
            headlines = live.recentNews().stream()
                    .map(h -> new HeadlineView(h.atMillis(), h.outlet(), h.title(), h.url(), h.tickers(), h.untracked()))
                    .toList();
        }
        return new DiscoveryView(true, news, rows, headlines);
    }
}
