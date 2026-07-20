package io.jethro.app.training;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs the ADR-0053 walk-forward backtest gate on demand and caches the verdict. The compute is heavy
 * (fit a model per fold over the whole feature set), so — learning from the hourly-OOS heap stall that
 * froze the box — it NEVER runs on a timer and never on the request thread: an endpoint triggers a
 * single background run, staleness-gated, and readers get the last cached {@link Result}. The result is
 * an advisory verdict (does the learned signal earn a place behind the deterministic OOS gate), never a
 * number into live sizing/risk (ADR-0016 / invariant 7).
 */
public final class LearnedSignalService {

    private static final Logger log = LoggerFactory.getLogger(LearnedSignalService.class);
    private static final long STALE_MILLIS = 10 * 60 * 1000L; // recompute at most every 10 min

    private final FeatureService features;
    private final WalkForwardBacktest.Config config;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Result cached = Result.idle();

    public LearnedSignalService(FeatureService features, WalkForwardBacktest.Config config) {
        this.features = features;
        this.config = config;
    }

    /** Cached verdict + run state. {@code result} is null until the first run finishes. */
    public record Result(String state, long computedAtMillis, WalkForwardBacktest.Result result) {
        static Result idle() {
            return new Result("idle", 0, null);
        }
    }

    public Result current() {
        maybeRefresh();
        return cached;
    }

    /** Kicks a background run if none is in flight and the cache is empty or stale. */
    private void maybeRefresh() {
        boolean stale = cached.result() == null || System.currentTimeMillis() - cached.computedAtMillis() > STALE_MILLIS;
        if (!stale || !running.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                cached = new Result("running", cached.computedAtMillis(), cached.result());
                var r = WalkForwardBacktest.run(features.allRows(), config);
                cached = new Result("done", System.currentTimeMillis(), r);
                log.info("learned-signal backtest (ADR-0053): {}", r.verdict());
            } catch (Exception e) {
                log.warn("learned-signal backtest failed: {}", e.toString());
                cached = new Result("error", System.currentTimeMillis(), cached.result());
            } finally {
                running.set(false);
            }
        }, "learned-signal-backtest");
        t.setDaemon(true);
        t.start();
    }
}
