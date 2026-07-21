package io.jethro.app.signal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Drives {@link SignalTelemetry#resolveDue()} on a cadence, off the request/market paths (ADR-0055
 * phase 1). Runs on the SHARED scheduled pool (footprint §7.3) rather than its own thread; every tick
 * is best-effort and swallows its own errors so a bad mark read can never disturb anything else. Pure
 * bookkeeping — it moves no orders and touches no risk.
 */
public final class SignalTelemetryResolver implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SignalTelemetryResolver.class);

    private final SignalTelemetry telemetry;
    private final ScheduledExecutorService scheduler;
    private final long intervalSeconds;
    private Future<?> task;

    public SignalTelemetryResolver(SignalTelemetry telemetry, ScheduledExecutorService scheduler, long intervalSeconds) {
        this.telemetry = telemetry;
        this.scheduler = scheduler;
        this.intervalSeconds = Math.max(5, intervalSeconds);
    }

    public void start() {
        task = scheduler.scheduleWithFixedDelay(this::tick, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("signal telemetry resolver started (every {}s, shared pool) — ADR-0055 phase 1", intervalSeconds);
    }

    private void tick() {
        try {
            telemetry.resolveDue();
        } catch (Exception e) {
            log.debug("signal telemetry resolve tick failed: {}", e.toString());
        }
    }

    @Override
    public void close() {
        if (task != null) {
            task.cancel(false); // cancel our task only — the shared pool is owned elsewhere
        }
    }
}
