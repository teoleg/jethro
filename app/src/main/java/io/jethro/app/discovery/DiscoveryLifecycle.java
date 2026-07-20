package io.jethro.app.discovery;

import io.jethro.app.social.Cashtags;
import io.jethro.trading.riskpnl.InstrumentRefSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Polls real news outlets and feeds UNTRACKED instrument mentions into the {@link UniverseCandidates}
 * register (ADR-0045/0050 §7): big outlets proposing names for the base list. Tracked names in the
 * news are left for the (future) SLM sector-classify/advisory path; here we only surface what we do
 * NOT yet track, as suggestions. Never adds an instrument, never trades. Off the tick path.
 */
public final class DiscoveryLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryLifecycle.class);

    // Run the first poll soon after boot (not after a full interval) so the UI shows the real source
    // status within seconds instead of sitting on "not polled yet" for 5 minutes. Small delay lets
    // boot finish first; the feed is off the tick path (MIN_PRIORITY). My scheduling knob, not a dial.
    private static final long INITIAL_DELAY_SECONDS = 20;

    private final RssNewsFeed news;
    private final UniverseCandidates candidates;
    private final InstrumentRefSource refs;
    private final DiscoveryProperties props;
    private final CompanyDirectory companies;
    private ScheduledExecutorService scheduler;

    public DiscoveryLifecycle(RssNewsFeed news, UniverseCandidates candidates,
                              InstrumentRefSource refs, DiscoveryProperties props, CompanyDirectory companies) {
        this.news = news;
        this.candidates = candidates;
        this.refs = refs;
        this.props = props;
        this.companies = companies;
    }

    @Override
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "discovery");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        long interval = props.intervalSecondsOrDefault();
        scheduler.scheduleWithFixedDelay(this::runOnce, INITIAL_DELAY_SECONDS, interval, TimeUnit.SECONDS);
        log.info("universe discovery started (ADR-0050 §7): {} news outlet(s), {} companies in the name directory, "
                        + "first poll in {}s then every {}s, suggestions only",
                props.outletsOrEmpty().size(), companies.size(), INITIAL_DELAY_SECONDS, interval);
    }

    private void runOnce() {
        try {
            long now = System.currentTimeMillis();
            Set<String> tracked = refs.instrumentIds();
            var items = news.poll(now);
            int discovered = 0;
            for (NewsItem item : items) {
                // Tickers named three ways: $cashtags, exchange-qualified "(NASDAQ: X)", and bare
                // company NAMES resolved via the curated directory (what most headlines actually use).
                Set<String> mentioned = Cashtags.extractNewsTickers(item.text());
                mentioned.addAll(companies.resolve(item.text()));
                for (String ticker : mentioned) {
                    if (!tracked.contains(ticker)) { // only propose names we DON'T already track
                        candidates.observe(ticker, "news:" + item.outlet(), props.newsWeightOrDefault(),
                                item.title(), now);
                        discovered++;
                    }
                }
            }
            if (discovered > 0) {
                log.info("universe discovery: {} untracked mention(s) from {} news item(s)", discovered, items.size());
            }
        } catch (Throwable t) {
            log.warn("discovery cycle failed: {}", t.toString());
        }
    }

    /** The RSS source's connection health, for the UI. */
    public io.jethro.app.social.SocialSourceStatus newsStatus() {
        return news.status();
    }

    @Override
    public void stop() {
        var s = scheduler;
        if (s != null) {
            s.shutdownNow();
            scheduler = null;
        }
    }

    @Override
    public boolean isRunning() {
        return scheduler != null;
    }
}
