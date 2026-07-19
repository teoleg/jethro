package io.jethro.app.social;

import io.jethro.app.trading.TradingCoreProperties;
import io.jethro.trading.riskpnl.InstrumentRef;
import io.jethro.trading.riskpnl.InstrumentRefSource;
import io.jethro.uigateway.AttentionFeed;
import io.jethro.uigateway.SseBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs the ADR-0050 social pipeline on a cadence: poll the (sim) feed → deterministic spam pre-filter
 * → corroboration gate → advisory signals, surfaced as CONTEXT. It never places an order and never
 * feeds a number into sizing/risk (ADR-0049 / ADR-0016 / invariant 7) — a social-derived subject is
 * information only. Off the tick path; a slow/empty cycle just means no new social context.
 */
public final class SocialLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SocialLifecycle.class);
    private static final int RECENT_CAP = 60;
    private static final int SEEN_HASH_CAP = 4_000;

    private final SocialFeed feed;
    private final SocialChannels channels;
    private final SpamFilter spam;
    private final InstrumentRefSource refs;
    private final TradingCoreProperties sim;
    private final AttentionFeed attention;
    private final SseBroadcaster sse;
    private final SocialProperties props;

    private final AtomicLong ingested = new AtomicLong();
    private final AtomicLong kept = new AtomicLong();
    private final AtomicLong duplicateDropped = new AtomicLong();
    private final AtomicLong cashtagSpamDropped = new AtomicLong();
    private final AtomicLong corroborated = new AtomicLong();
    private final AtomicLong manipulationSuspected = new AtomicLong();

    private final Deque<SocialPost> recent = new ArrayDeque<>();        // newest first, kept posts
    private final Set<String> seenHashes = new LinkedHashSet<>();       // cross-batch dedup memory
    private volatile List<SocialSignal> signals = List.of();
    private volatile long lastRunMillis;
    private ScheduledExecutorService scheduler;

    public SocialLifecycle(SocialFeed feed, SocialChannels channels, SpamFilter spam,
                           InstrumentRefSource refs, TradingCoreProperties sim, AttentionFeed attention,
                           SseBroadcaster sse, SocialProperties props) {
        this.feed = feed;
        this.channels = channels;
        this.spam = spam;
        this.refs = refs;
        this.sim = sim;
        this.attention = attention;
        this.sse = sse;
        this.props = props;
    }

    @Override
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "social");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY); // background chore; never latency-critical
            return t;
        });
        long interval = props.intervalSecondsOrDefault();
        scheduler.scheduleWithFixedDelay(this::runOnce, interval, interval, TimeUnit.SECONDS);
        log.info("social source started (ADR-0050): sim feed, every {}s, corroboration k={}, advisory-only "
                + "(never an order)", interval, props.kOrDefault());
    }

    private void runOnce() {
        try {
            long now = System.currentTimeMillis();
            List<String> equities = new ArrayList<>();
            Set<String> universe = new LinkedHashSet<>();
            for (String id : sim.simInstruments()) {
                refs.find(id).filter(r -> "EQUITY".equals(r.assetClass())).ifPresent(r -> {
                    equities.add(id);
                    universe.add(id);
                });
            }
            if (universe.isEmpty()) {
                return;
            }
            List<SocialPost> posts = feed.poll(equities, now);
            SpamFilter.Result result = spam.filter(posts, seenHashes, universe);
            List<SocialSignal> next = CorroborationGate.evaluate(result.kept(), universe, this::sectorOf,
                    channels, props.kOrDefault(), props.burstThresholdOrDefault());

            ingested.addAndGet(posts.size());
            kept.addAndGet(result.kept().size());
            duplicateDropped.addAndGet(result.dropped().getOrDefault(SpamFilter.Drop.DUPLICATE, 0));
            cashtagSpamDropped.addAndGet(result.dropped().getOrDefault(SpamFilter.Drop.CASHTAG_SPAM, 0));
            long promoted = next.stream().filter(s -> !s.manipulationSuspected()).count();
            long pumps = next.size() - promoted;
            corroborated.addAndGet(promoted);
            manipulationSuspected.addAndGet(pumps);

            synchronized (recent) {
                for (SocialPost p : result.kept()) {
                    recent.addFirst(p);
                }
                while (recent.size() > RECENT_CAP) {
                    recent.removeLast();
                }
            }
            trimSeen();
            signals = next;
            lastRunMillis = now;

            surface(next);
            log.info("social: ingested {}, kept {}, dropped {} dup / {} shill, corroborated {}, pump-flagged {}",
                    posts.size(), result.kept().size(),
                    result.dropped().getOrDefault(SpamFilter.Drop.DUPLICATE, 0),
                    result.dropped().getOrDefault(SpamFilter.Drop.CASHTAG_SPAM, 0), promoted, pumps);
        } catch (Throwable t) {
            log.warn("social cycle failed: {}", t.toString());
        }
    }

    private String sectorOf(String instrumentId) {
        return refs.find(instrumentId).map(InstrumentRef::hedgeGroup)
                .filter(s -> s != null && !s.isBlank()).orElse("—");
    }

    /** Surface corroborated context and any pump tell on the attention feed — INFO/WARN, never a trade. */
    private void surface(List<SocialSignal> next) {
        long promoted = next.stream().filter(s -> !s.manipulationSuspected()).count();
        List<SocialSignal> pumps = next.stream().filter(SocialSignal::manipulationSuspected).toList();
        String id = "social:summary";
        if (promoted == 0 && pumps.isEmpty()) {
            attention.resolve(id);
            sse.broadcast("attention", attention.snapshot());
            return;
        }
        AttentionFeed.Severity sev = pumps.isEmpty() ? AttentionFeed.Severity.INFO : AttentionFeed.Severity.WARN;
        StringBuilder body = new StringBuilder();
        if (promoted > 0) {
            body.append(promoted).append(" corroborated social subject(s) — advisory context only (ADR-0050), no order. ");
        }
        if (!pumps.isEmpty()) {
            body.append("Suspected manipulation on ").append(pumps.stream()
                    .map(SocialSignal::instrumentId).distinct().toList())
                    .append(" — failed corroboration, ignored.");
        }
        attention.upsert(new AttentionFeed.AttentionItem(id, System.currentTimeMillis(), sev,
                "social", "Social signals", body.toString().trim(), "/social.html"));
        sse.broadcast("attention", attention.snapshot());
    }

    private void trimSeen() {
        synchronized (seenHashes) {
            if (seenHashes.size() <= SEEN_HASH_CAP) {
                return;
            }
            var it = seenHashes.iterator();
            while (seenHashes.size() > SEEN_HASH_CAP && it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }

    /** Per-source connection health for the UI (ADR-0050) — flattened across the composite. */
    public List<SocialSourceStatus> sourceHealth() {
        return feed.health();
    }

    public List<SocialSignal> signals() {
        return signals;
    }

    public List<SocialPost> recentPosts() {
        synchronized (recent) {
            return List.copyOf(recent);
        }
    }

    public long lastRunMillis() {
        return lastRunMillis;
    }

    public long[] counters() {
        return new long[]{ingested.get(), kept.get(), duplicateDropped.get(), cashtagSpamDropped.get(),
                corroborated.get(), manipulationSuspected.get()};
    }

    /** Registry tier for a channel (for the UI). */
    public String tierOf(String channel) {
        return channels.tierOf(channel).name();
    }

    /** Whether a post would count toward corroboration (for the UI). */
    public boolean credible(SocialPost p) {
        return channels.isCredible(p);
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
