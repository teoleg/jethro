package io.jethro.app.risk;

import io.jethro.trading.riskpnl.LimitBreach;
import io.jethro.trading.riskpnl.RiskLimitEvaluator;
import io.jethro.trading.riskpnl.RiskLimitSource;
import io.jethro.trading.riskpnl.RiskProjection;
import io.jethro.uigateway.AttentionFeed;
import io.jethro.uigateway.SseBroadcaster;
import org.springframework.context.SmartLifecycle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Deterministic risk-limit triggers for the attention floor (ADR-0017): evaluates the
 * risk snapshot against configured limits and upserts WARN/ALERT cards into the shared
 * attention feed — no model between a breach and the screen (invariant 7). Cleared
 * breaches are resolved. Broadcasts on membership change so a new/cleared breach shows
 * promptly; the ui-gateway's periodic broadcast refreshes the live values.
 */
public final class RiskLimitMonitor implements SmartLifecycle {

    private static final long EVERY_MILLIS = 2_000;

    private final RiskProjection projection;
    private final RiskLimitEvaluator evaluator;
    private final RiskLimitSource limits;
    private final AttentionFeed feed;
    private final SseBroadcaster sse;

    private final Set<String> active = new HashSet<>();
    private volatile ScheduledExecutorService scheduler;

    public RiskLimitMonitor(RiskProjection projection, RiskLimitEvaluator evaluator,
                            RiskLimitSource limits, AttentionFeed feed, SseBroadcaster sse) {
        this.projection = projection;
        this.evaluator = evaluator;
        this.limits = limits;
        this.feed = feed;
        this.sse = sse;
    }

    @Override
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "risk-limit-monitor");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::evaluate, EVERY_MILLIS, EVERY_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void evaluate() {
        long now = System.currentTimeMillis();
        List<LimitBreach> breaches = evaluator.evaluate(projection.snapshot(now), limits);

        Set<String> current = new HashSet<>();
        for (LimitBreach breach : breaches) {
            current.add(breach.id());
            feed.upsert(toItem(breach, now));
        }
        boolean changed = false;
        for (String id : Set.copyOf(active)) {
            if (!current.contains(id)) {
                feed.resolve(id);
                active.remove(id);
                changed = true;
            }
        }
        for (String id : current) {
            if (active.add(id)) {
                changed = true;
            }
        }
        if (changed) {
            sse.broadcast("attention", feed.snapshot());
        }
    }

    private static AttentionFeed.AttentionItem toItem(LimitBreach b, long now) {
        String metric = switch (b.metric()) {
            case GROSS_EXPOSURE -> "gross exposure";
            case NET_EXPOSURE -> "net exposure";
            case LOSS -> "loss";
        };
        String title = b.bookId() + " "
                + (b.severity() == LimitBreach.Severity.ALERT ? "at/over " : "approaching ") + metric + " limit";
        String body = capitalize(metric) + " " + plain(b.actual()) + " vs limit " + plain(b.limit())
                + " (" + pct(b.actual(), b.limit()) + "% of limit).";
        return new AttentionFeed.AttentionItem(b.id(), now,
                AttentionFeed.Severity.valueOf(b.severity().name()), "risk-limit", title, body, "/books.html");
    }

    private static String plain(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String pct(BigDecimal actual, BigDecimal limit) {
        return actual.multiply(BigDecimal.valueOf(100)).divide(limit, 0, RoundingMode.HALF_UP).toPlainString();
    }

    private static String capitalize(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
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
