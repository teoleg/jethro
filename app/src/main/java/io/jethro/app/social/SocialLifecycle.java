package io.jethro.app.social;

import io.jethro.domain.Side;
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
    private final AttentionFeed attention;
    private final SseBroadcaster sse;
    private final SocialProperties props;
    private final io.jethro.app.discovery.UniverseCandidates candidates; // nullable — discovery register
    private final double discoverySocialWeight;

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
                           InstrumentRefSource refs, AttentionFeed attention,
                           SseBroadcaster sse, SocialProperties props,
                           io.jethro.app.discovery.UniverseCandidates candidates, double discoverySocialWeight) {
        this.feed = feed;
        this.channels = channels;
        this.spam = spam;
        this.refs = refs;
        this.attention = attention;
        this.sse = sse;
        this.props = props;
        this.candidates = candidates;
        this.discoverySocialWeight = discoverySocialWeight;
    }

    // ADR-0055 phase 1: optional health telemetry — an observer social never depends on.
    private volatile io.jethro.app.signal.SignalTelemetry signalTelemetry;
    private volatile io.jethro.app.fusion.ForecastRegistry forecastRegistry; // ADR-0055 phase 4 (shadow)

    public void setSignalTelemetry(io.jethro.app.signal.SignalTelemetry signalTelemetry) {
        this.signalTelemetry = signalTelemetry;
    }

    public void setForecastRegistry(io.jethro.app.fusion.ForecastRegistry forecastRegistry) {
        this.forecastRegistry = forecastRegistry;
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
        // First poll soon after boot (not after a full interval) so per-source status is real within
        // seconds rather than "not polled yet". A short delay lets boot finish; feed is MIN_PRIORITY.
        long initialDelay = Math.min(15, interval);
        scheduler.scheduleWithFixedDelay(this::runOnce, initialDelay, interval, TimeUnit.SECONDS);
        log.info("social source started (ADR-0050): sim feed, first poll in {}s then every {}s, corroboration k={}, "
                + "advisory-only (never an order)", initialDelay, interval, props.kOrDefault());
    }

    private void runOnce() {
        try {
            long now = System.currentTimeMillis();
            // The TRACKED set is the REAL reference-data equity master — independent of any market-data
            // feed/sim (ADR-0050): social is an always-online external source, unrelated to price
            // simulation. It is used only to (a) give the real adapters symbols to poll and (b) TAG a
            // discovered subject tracked-or-not — it does NOT restrict what social may surface (an
            // untracked name that's "cooking" is a suggestion to add, decided later). Sorted for a
            // stable round-robin in the adapters.
            List<String> pollSymbols = new ArrayList<>();
            Set<String> tracked = new LinkedHashSet<>();
            refs.instrumentIds().stream().sorted().forEach(id ->
                    refs.find(id).filter(r -> "EQUITY".equals(r.assetClass())).ifPresent(r -> {
                        pollSymbols.add(id);
                        tracked.add(id);
                    }));
            List<SocialPost> posts = feed.poll(pollSymbols, now);
            SpamFilter.Result result = spam.filter(posts, seenHashes);
            List<SocialSignal> next = CorroborationGate.evaluate(result.kept(), tracked, this::sectorOf,
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

            // ADR-0055: record each tracked, directional (non-pump) social signal — phase 1 health
            // telemetry (scored by forward return) + phase 4 current forecast for the fusion layer.
            // Observational; social never orders.
            if (signalTelemetry != null || forecastRegistry != null) {
                for (SocialSignal sig : next) {
                    if (!sig.tracked() || sig.manipulationSuspected()) {
                        continue; // untracked (no mark) or a suspected pump — never a measured call
                    }
                    Side side = "BULLISH".equals(sig.direction()) ? Side.BUY
                            : "BEARISH".equals(sig.direction()) ? Side.SELL : null;
                    if (side == null) {
                        continue; // NEUTRAL — no view
                    }
                    if (signalTelemetry != null) {
                        signalTelemetry.record("social", sig.instrumentId(), side); // mark from live cache
                    }
                    if (forecastRegistry != null) {
                        forecastRegistry.submitSocial(sig); // current forecast for the fusion layer
                    }
                }
            }

            // Feed UNTRACKED corroborated names to the discovery register (ADR-0050 §7) as candidate
            // additions — social "adds more if something is cooking". A suspected pump is NEVER a
            // suggestion. Only when the discovery register is present.
            if (candidates != null) {
                for (SocialSignal sig : next) {
                    if (!sig.tracked() && !sig.manipulationSuspected()) {
                        candidates.observe(sig.instrumentId(), "social",
                                discoverySocialWeight * Math.max(1, sig.corroboratingChannels()), sig.sample(), now);
                    }
                }
            }
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
