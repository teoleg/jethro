package io.jethro.app.trading;

import io.jethro.uigateway.AttentionFeed;
import io.jethro.uigateway.SseBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Surfaces mark quarantines (corporate action / bad print, see {@code MarkCache}) as ALERT
 * cards on the attention feed — deterministic floor, a model can never suppress these
 * (ADR-0017). One card per quarantined instrument, resolved when an operator clears it via
 * {@code POST /api/marks/{id}/clear-quarantine}.
 */
public final class MarkQuarantineMonitor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MarkQuarantineMonitor.class);
    private static final long PERIOD_SECONDS = 5;

    private final TradingCoreLifecycle tradingCore;
    private final AttentionFeed feed;
    private final SseBroadcaster sse;
    private final Set<String> active = new HashSet<>();
    private volatile ScheduledExecutorService scheduler;

    public MarkQuarantineMonitor(TradingCoreLifecycle tradingCore, AttentionFeed feed, SseBroadcaster sse) {
        this.tradingCore = tradingCore;
        this.feed = feed;
        this.sse = sse;
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mark-quarantine");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::checkOnce, PERIOD_SECONDS, PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    void checkOnce() {
        try {
            var runtime = tradingCore.runtime();
            if (runtime == null) {
                return;
            }
            Set<String> current = new HashSet<>();
            boolean changed = false;
            long now = System.currentTimeMillis();
            for (var q : runtime.markCache().quarantined()) {
                String cardId = "quarantine:" + q.instrumentId();
                current.add(cardId);
                feed.upsert(new AttentionFeed.AttentionItem(cardId, now,
                        AttentionFeed.Severity.ALERT, "mark-quarantine",
                        q.instrumentId() + " quarantined — possible corporate action or bad print",
                        "Mark jumped from " + q.lastGoodPrice().toPlainString() + " to "
                                + q.suspectPrice().toPlainString() + " in one update. The suspect price is "
                                + "NOT applied: positions/P&L/orders freeze at the last good mark and the "
                                + "instrument won't trade on the new level. If this is a real split/reprice, "
                                + "clear it: POST /api/marks/" + q.instrumentId() + "/clear-quarantine.",
                        "/markets.html"));
                if (active.add(cardId)) {
                    changed = true;
                }
            }
            for (String cardId : Set.copyOf(active)) {
                if (!current.contains(cardId)) {
                    feed.resolve(cardId);
                    active.remove(cardId);
                    changed = true;
                }
            }
            if (changed) {
                sse.broadcast("attention", feed.snapshot());
            }
        } catch (Exception e) {
            log.warn("quarantine monitor pass failed: {}", e.toString());
        }
    }

    @Override
    public void close() {
        var s = scheduler;
        if (s != null) {
            s.shutdownNow();
        }
    }
}
