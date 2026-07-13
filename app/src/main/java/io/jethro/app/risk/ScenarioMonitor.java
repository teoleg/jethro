package io.jethro.app.risk;

import io.jethro.trading.riskpnl.ConsolidatedRisk;
import io.jethro.trading.riskpnl.RiskProjection;
import io.jethro.trading.riskpnl.ScenarioEngine;
import io.jethro.uigateway.AttentionFeed;
import io.jethro.uigateway.SseBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Scenario results become attention triggers (quant-engine step 2 → step 5): when the
 * worst standard stress would lose more than the firm loss cap, that's tomorrow's
 * problem visible today — surfaced as a deterministic WARN/ALERT card (ADR-0017 floor;
 * no model may suppress it, invariant 7). WARN at warn-ratio × cap, ALERT at/over cap;
 * the card resolves when positions shrink back under.
 */
public final class ScenarioMonitor implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ScenarioMonitor.class);
    private static final long EVERY_MILLIS = 15_000;
    private static final String CARD_ID = "scenario-stress";

    private final RiskProjection projection;
    private final ScenarioEngine engine;
    private final RiskLimitProperties limits;
    private final AttentionFeed feed;
    private final SseBroadcaster sse;

    private boolean cardActive;
    private volatile ScheduledExecutorService scheduler;

    public ScenarioMonitor(RiskProjection projection, ScenarioEngine engine,
                           RiskLimitProperties limits, AttentionFeed feed, SseBroadcaster sse) {
        this.projection = projection;
        this.engine = engine;
        this.limits = limits;
        this.feed = feed;
        this.sse = sse;
    }

    @Override
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "scenario-monitor");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::evaluate, EVERY_MILLIS, EVERY_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void evaluate() {
        try {
            BigDecimal cap = firmLossCap();
            if (cap == null || cap.signum() <= 0) {
                return; // no firm loss cap configured — nothing to compare against
            }
            long now = System.currentTimeMillis();
            ConsolidatedRisk snapshot = projection.snapshot(now);
            List<ScenarioEngine.ScenarioResult> results = engine.run(snapshot.positions(), projection.fx());
            ScenarioEngine.ScenarioResult worst = null;
            for (ScenarioEngine.ScenarioResult r : results) {
                if (worst == null || r.firmPnlUsd().compareTo(worst.firmPnlUsd()) < 0) {
                    worst = r;
                }
            }
            if (worst == null || worst.firmPnlUsd().signum() >= 0) {
                resolveIfActive();
                return;
            }
            BigDecimal loss = worst.firmPnlUsd().negate();
            BigDecimal warnAt = cap.multiply(limits.warnRatioOrDefault());
            if (loss.compareTo(warnAt) < 0) {
                resolveIfActive();
                return;
            }
            AttentionFeed.Severity severity = loss.compareTo(cap) >= 0
                    ? AttentionFeed.Severity.ALERT : AttentionFeed.Severity.WARN;
            String worstBook = worst.byBook().isEmpty() ? "—" : worst.byBook().get(0).bookId();
            feed.upsert(new AttentionFeed.AttentionItem(CARD_ID, now, severity, "scenario-stress",
                    "Stress: “" + worst.name() + "” would lose $" + money(loss),
                    "The worst standard scenario would cost $" + money(loss) + " ("
                            + pct(loss, cap) + "% of the firm loss cap $" + money(cap)
                            + "), hit hardest in " + worstBook
                            + ". First-order revaluation of live positions — details on the Overview stress panel.",
                    "/"));
            if (!cardActive) {
                cardActive = true;
                sse.broadcast("attention", feed.snapshot());
            }
        } catch (Throwable t) {
            // Never let one bad cycle cancel the schedule silently.
            log.warn("scenario monitor cycle failed: {}", t.toString());
        }
    }

    private void resolveIfActive() {
        if (cardActive) {
            feed.resolve(CARD_ID);
            cardActive = false;
            sse.broadcast("attention", feed.snapshot());
        }
    }

    private BigDecimal firmLossCap() {
        return limits.firm() != null ? limits.firm().maxLossPnl() : null;
    }

    private static String money(BigDecimal v) {
        return v.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    private static String pct(BigDecimal actual, BigDecimal limit) {
        return actual.multiply(BigDecimal.valueOf(100)).divide(limit, 0, RoundingMode.HALF_UP).toPlainString();
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
