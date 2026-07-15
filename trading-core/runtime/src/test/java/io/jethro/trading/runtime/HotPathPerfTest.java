package io.jethro.trading.runtime;

import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Performance guardrails for the tick hot path (see docs/architecture/perf-budgets.md).
 * Two kinds of assertion, both regression guards rather than benchmarks:
 *
 * <ul>
 *   <li><b>Allocation-free:</b> the ring buffer and the mark cache promise zero allocation
 *       per tick (ADR-0014, hot-path convention). Measured with the JVM's precise per-thread
 *       TLAB accounting ({@code com.sun.management.ThreadMXBean}); the budget is a small
 *       fixed slack for JIT/deopt noise, ~0.1 bytes/op — one real allocation per op would
 *       blow it by orders of magnitude (16 MB+ over a million ops).</li>
 *   <li><b>Throughput floors:</b> deliberately 20–50× below what even the 2-vCPU shared CI
 *       runner measures, so scheduler noise never flakes the build, while an accidental
 *       O(n) scan, lock, or boxing regression on the per-tick path still fails loudly.
 *       Measured rates are printed for trend-watching in CI logs.</li>
 * </ul>
 *
 * <p>JMH is the right tool for real benchmarking; it is deliberately not used here — these
 * run inside the ordinary unit-test task on every build, and their job is only to catch
 * order-of-magnitude regressions, not to rank nanoseconds.
 */
class HotPathPerfTest {

    private static final int WARMUP_OPS = 200_000;
    private static final int MEASURED_OPS = 1_000_000;
    /** Slack for JIT recompilation/deopt noise; a single real alloc/op ≈ 16 MB over the run. */
    private static final long ALLOCATION_SLACK_BYTES = 128 * 1024;
    /** Floors ~20–50× under measured rates on a 2-vCPU CI runner (see perf-budgets.md). */
    private static final long RING_FLOOR_OPS_PER_SEC = 1_000_000;
    private static final long MARK_FLOOR_OPS_PER_SEC = 1_000_000;

    /** Blackhole: consumed by the poll visitor so the JIT cannot dead-code the reads. */
    private static long sink;

    private static final TickRingBuffer.TickVisitor SINK_VISITOR =
            slot -> sink += slot.priceScaled() + slot.qtyScaled();

    private static com.sun.management.ThreadMXBean allocationMeter() {
        var mx = ManagementFactory.getThreadMXBean();
        assumeTrue(mx instanceof com.sun.management.ThreadMXBean,
                "JVM without com.sun.management thread allocation accounting");
        var sun = (com.sun.management.ThreadMXBean) mx;
        assumeTrue(sun.isThreadAllocatedMemorySupported(), "thread allocation accounting unsupported");
        if (!sun.isThreadAllocatedMemoryEnabled()) {
            sun.setThreadAllocatedMemoryEnabled(true);
        }
        return sun;
    }

    // ---- TickRingBuffer ----

    private static void ringOfferPoll(TickRingBuffer ring, int ops) {
        for (int i = 0; i < ops; i++) {
            ring.offer("ES", 5_450_000_000L + i, 1_000_000L, i, i);
            ring.poll(SINK_VISITOR);
        }
    }

    @Test
    void ringBufferOfferPollAllocatesNothingPerTick() {
        var meter = allocationMeter();
        var ring = new TickRingBuffer(1024);
        ringOfferPoll(ring, WARMUP_OPS); // JIT + lazy init outside the measured window

        long tid = Thread.currentThread().threadId();
        long before = meter.getThreadAllocatedBytes(tid);
        ringOfferPoll(ring, MEASURED_OPS);
        long bytes = meter.getThreadAllocatedBytes(tid) - before;

        System.out.printf("[perf] ring buffer offer+poll: %,d bytes over %,d ops (%.4f B/op)%n",
                bytes, MEASURED_OPS, (double) bytes / MEASURED_OPS);
        assertTrue(bytes < ALLOCATION_SLACK_BYTES,
                "ring buffer allocated " + bytes + " bytes over " + MEASURED_OPS
                        + " offer+poll ops — the tick path must not allocate (ADR-0014)");
    }

    @Test
    void ringBufferThroughputStaysOrdersOfMagnitudeAboveTheTape() {
        var ring = new TickRingBuffer(1024);
        ringOfferPoll(ring, WARMUP_OPS);

        long start = System.nanoTime();
        ringOfferPoll(ring, MEASURED_OPS);
        long nanos = System.nanoTime() - start;

        long opsPerSec = MEASURED_OPS * 1_000_000_000L / Math.max(1, nanos);
        System.out.printf("[perf] ring buffer offer+poll: %,d pairs/s (floor %,d)%n",
                opsPerSec, RING_FLOOR_OPS_PER_SEC);
        assertTrue(opsPerSec > RING_FLOOR_OPS_PER_SEC,
                "ring buffer degraded to " + opsPerSec + " offer+poll pairs/s — floor "
                        + RING_FLOOR_OPS_PER_SEC + " is already 20–50× under a healthy build");
        assertTrue(ring.droppedCount() == 0, "offer+poll in lockstep must never drop");
    }

    // ---- MarkCache (jump guard enabled — the production configuration) ----

    private static final MarkCache.JumpThresholds EQUITY_GUARD = id -> 2000; // 20%

    private static void markUpdates(MarkCache cache, int ops) {
        // Oscillate ±1e-6 around the level: every update exercises the full guard
        // arithmetic (|Δ|×10⁴ vs prev×bps cross-multiplication) and is accepted.
        for (int i = 0; i < ops; i++) {
            cache.update("AAPL", 190_000_000L + (i & 1), i, i, "sim");
        }
    }

    @Test
    void markCacheAcceptedUpdateAllocatesNothingPerTick() {
        var meter = allocationMeter();
        var cache = new MarkCache(EQUITY_GUARD);
        markUpdates(cache, WARMUP_OPS);

        long tid = Thread.currentThread().threadId();
        long before = meter.getThreadAllocatedBytes(tid);
        markUpdates(cache, MEASURED_OPS);
        long bytes = meter.getThreadAllocatedBytes(tid) - before;

        System.out.printf("[perf] mark cache accepted update: %,d bytes over %,d ops (%.4f B/op)%n",
                bytes, MEASURED_OPS, (double) bytes / MEASURED_OPS);
        assertTrue(bytes < ALLOCATION_SLACK_BYTES,
                "MarkCache.update allocated " + bytes + " bytes over " + MEASURED_OPS
                        + " accepted updates — one allocation per instrument lifetime, none per tick");
    }

    @Test
    void markCacheQuarantineRejectPathAllocatesNothingPerTick() {
        var meter = allocationMeter();
        var cache = new MarkCache(EQUITY_GUARD);
        cache.update("AAPL", 190_000_000L, 0, 0, "sim");
        cache.update("AAPL", 400_000_000L, 1, 1, "sim"); // trips the guard (one log.warn here)
        assertTrue(cache.get("AAPL").quarantined(), "fixture must be in the quarantined state");

        for (int i = 0; i < WARMUP_OPS; i++) { // warm the reject path itself
            cache.update("AAPL", 400_000_000L + i, i, i, "sim");
        }
        long tid = Thread.currentThread().threadId();
        long before = meter.getThreadAllocatedBytes(tid);
        for (int i = 0; i < MEASURED_OPS; i++) {
            cache.update("AAPL", 400_000_000L + i, i, i, "sim");
        }
        long bytes = meter.getThreadAllocatedBytes(tid) - before;

        System.out.printf("[perf] mark cache quarantined reject: %,d bytes over %,d ops (%.4f B/op)%n",
                bytes, MEASURED_OPS, (double) bytes / MEASURED_OPS);
        assertTrue(bytes < ALLOCATION_SLACK_BYTES,
                "the quarantine reject path allocated " + bytes + " bytes over " + MEASURED_OPS
                        + " ops — a quarantined instrument must not turn the tick path allocatey");
        assertTrue(cache.rejectedTicks() > MEASURED_OPS, "every rejected tick is counted, never silent");
    }

    @Test
    void markCacheUpdateThroughputStaysOrdersOfMagnitudeAboveTheTape() {
        var cache = new MarkCache(EQUITY_GUARD);
        markUpdates(cache, WARMUP_OPS);

        long start = System.nanoTime();
        markUpdates(cache, MEASURED_OPS);
        long nanos = System.nanoTime() - start;

        long opsPerSec = MEASURED_OPS * 1_000_000_000L / Math.max(1, nanos);
        System.out.printf("[perf] mark cache update (guard on): %,d ops/s (floor %,d)%n",
                opsPerSec, MARK_FLOOR_OPS_PER_SEC);
        assertTrue(opsPerSec > MARK_FLOOR_OPS_PER_SEC,
                "MarkCache.update degraded to " + opsPerSec + " ops/s — floor "
                        + MARK_FLOOR_OPS_PER_SEC + " is already 20–50× under a healthy build");
    }
}
