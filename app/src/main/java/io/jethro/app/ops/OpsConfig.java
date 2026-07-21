package io.jethro.app.ops;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 * Operational infrastructure (ADR-0055 follow-up / footprint §7.3, §7.6): the stall watchdog and a
 * single SHARED scheduled pool. Each subsystem historically spun its own single-thread executor (~28 of
 * them); a shared bounded pool caps that proliferation so one slow task can't monopolise a thread and
 * the total thread count stays fixed. New periodic work uses this; migrating the older lifecycles onto
 * it is a follow-up (footprint §7.3). The tick loop stays on its own dedicated thread — never shared.
 */
@Configuration
public class OpsConfig {

    /** Shared scheduled pool for light periodic app work (fusion, signal telemetry, …). */
    @Bean(name = "sharedScheduler", destroyMethod = "shutdownNow")
    ScheduledExecutorService sharedScheduler(@Value("${jethro.scheduler.shared-threads:3}") int threads) {
        var pool = new ScheduledThreadPoolExecutor(Math.max(2, threads), r -> {
            Thread t = new Thread(r, "jethro-sched");
            t.setDaemon(true);
            return t;
        });
        pool.setRemoveOnCancelPolicy(true); // cancelled tasks don't linger in the queue
        return pool;
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "jethro.watchdog", name = "enabled", havingValue = "true", matchIfMissing = true)
    StallWatchdog stallWatchdog(
            @Value("${jethro.watchdog.check-seconds:10}") long checkSeconds,
            @Value("${jethro.watchdog.stall-threshold-seconds:45}") long stallSeconds,
            @Value("${jethro.watchdog.pause-tolerance-seconds:10}") long pauseSeconds,
            @Value("${jethro.watchdog.dump-dir:logs}") String dumpDir) {
        var watchdog = new StallWatchdog(checkSeconds * 1_000, stallSeconds * 1_000,
                pauseSeconds * 1_000, Path.of(dumpDir));
        watchdog.start();
        return watchdog;
    }
}
