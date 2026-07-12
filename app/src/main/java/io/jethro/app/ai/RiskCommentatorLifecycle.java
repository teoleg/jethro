package io.jethro.app.ai;

import io.jethro.app.trading.TradingCoreLifecycle;
import io.jethro.trading.algo.agent.MarketView;
import io.jethro.trading.algo.agent.RiskCommentator;
import io.jethro.trading.algo.inference.InferenceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drives the risk commentator (ADR-0016) on a cadence: computed state in, commentary
 * out, AiDecision recorded per run. Degrades gracefully when the model is down —
 * the market path never depends on inference (invariant 7).
 */
public final class RiskCommentatorLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RiskCommentatorLifecycle.class);

    private final RiskCommentator commentator;
    private final TradingCoreLifecycle tradingCore;
    private final long intervalSeconds;
    private final AtomicLong consecutiveFailures = new AtomicLong();
    private volatile ScheduledExecutorService scheduler;

    public RiskCommentatorLifecycle(RiskCommentator commentator, TradingCoreLifecycle tradingCore,
                                    long intervalSeconds) {
        this.commentator = commentator;
        this.tradingCore = tradingCore;
        this.intervalSeconds = intervalSeconds;
    }

    @Override
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "risk-commentator");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::runOnce, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("risk commentator started: every {}s", intervalSeconds);
    }

    private void runOnce() {
        var runtime = tradingCore.runtime();
        if (runtime == null) {
            return;
        }
        try {
            String commentary = commentator.commentOn(marketView(runtime));
            consecutiveFailures.set(0);
            log.info("commentary: {}", commentary.strip());
        } catch (InferenceException e) {
            long failures = consecutiveFailures.incrementAndGet();
            if (failures == 1 || failures % 10 == 0) { // don't spam while the model is down
                log.warn("commentator inference failed ({} consecutive): {}", failures, e.getMessage());
            }
        } catch (Exception e) {
            log.error("commentator failed unexpectedly", e);
        }
    }

    private static MarketView marketView(io.jethro.trading.runtime.TradingCoreRuntime runtime) {
        long now = System.currentTimeMillis();
        List<MarketView.InstrumentMark> marks = new ArrayList<>();
        for (var mark : runtime.markCache().snapshot()) {
            marks.add(new MarketView.InstrumentMark(
                    mark.instrumentId(), mark.price(), mark.stale(),
                    now - mark.providerTimestamp().toEpochMilli()));
        }
        return new MarketView(marks, runtime.stats().ticksIn(), runtime.stats().ticksDropped());
    }

    @Override
    public void stop() {
        var s = scheduler;
        if (s != null) {
            s.shutdownNow();
            scheduler = null;
            log.info("risk commentator stopped");
        }
    }

    @Override
    public boolean isRunning() {
        return scheduler != null;
    }
}
