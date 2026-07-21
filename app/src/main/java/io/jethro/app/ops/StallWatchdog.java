package io.jethro.app.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Self-diagnosing freeze detector. The recurring whole-app stalls (idle CPU, I/O-wait spikes, reboot to
 * recover) were being diagnosed by inference; this makes the NEXT one leave evidence. Two independent
 * signals, so it catches both failure shapes:
 *
 * <ul>
 *   <li><b>App stall</b> — a normal-priority heartbeat task can no longer run (the executors are wedged
 *       on a lock, a deadlock, or the pool is exhausted): the heartbeat counter stops advancing.</li>
 *   <li><b>JVM-wide pause</b> — even this max-priority thread is starved (a long GC pause, or the box is
 *       thrashing on swap): its own sleep overruns by more than the tolerance.</li>
 * </ul>
 *
 * On either, it writes ONE dump: deadlocks (named), a thread-state histogram, the full thread dump with
 * lock info, heap + GC, and — the piece that settles the swap question — process RSS/swap and system
 * memory from {@code /proc} (best-effort, Linux). Cheap and allocation-light so it can run even while the
 * app is stuck; observational, it touches no app state.
 */
public final class StallWatchdog implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(StallWatchdog.class);

    private final long checkMillis;
    private final long stallThresholdMillis;
    private final long pauseToleranceMillis;
    private final Path dumpDir;

    private final AtomicLong heartbeat = new AtomicLong();
    private volatile long lastSeenHeartbeat;
    private volatile long lastHeartbeatChangeMillis;
    private volatile boolean stalled;
    private ScheduledExecutorService beat;
    private Thread watcher;

    public StallWatchdog(long checkMillis, long stallThresholdMillis, long pauseToleranceMillis, Path dumpDir) {
        this.checkMillis = Math.max(1_000, checkMillis);
        this.stallThresholdMillis = Math.max(this.checkMillis * 2, stallThresholdMillis);
        this.pauseToleranceMillis = Math.max(2_000, pauseToleranceMillis);
        this.dumpDir = dumpDir;
    }

    public void start() {
        long now = System.currentTimeMillis();
        lastHeartbeatChangeMillis = now;
        // Normal-priority heartbeat: represents "can the app still run a trivial scheduled task?".
        beat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "watchdog-heartbeat");
            t.setDaemon(true);
            return t;
        });
        beat.scheduleWithFixedDelay(heartbeat::incrementAndGet, checkMillis, checkMillis, TimeUnit.MILLISECONDS);
        // Native-memory trend: log an NMT summary every 20 min so a native leak (RSS climbing while heap
        // stays flat) localises itself — the category that grows over the run names the culprit. Needs
        // -XX:NativeMemoryTracking=summary at launch (run-local.sh sets it), else this logs "not enabled".
        beat.scheduleWithFixedDelay(() -> log.info("NATIVE MEMORY (NMT) trend: {}\n{}", classLoadingLine(), nmtSummary()),
                2, 20, TimeUnit.MINUTES);
        // Max-priority watcher: runs even under contention, so it can observe (and dump) a full stall.
        watcher = new Thread(this::loop, "stall-watchdog");
        watcher.setDaemon(true);
        watcher.setPriority(Thread.MAX_PRIORITY);
        watcher.start();
        log.info("stall watchdog started — check {}ms, stall≥{}ms, pause-tolerance {}ms, dumps → {}",
                checkMillis, stallThresholdMillis, pauseToleranceMillis, dumpDir.toAbsolutePath());
    }

    private void loop() {
        long prev = System.currentTimeMillis();
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(checkMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            long now = System.currentTimeMillis();
            long overrun = (now - prev) - checkMillis; // how much longer than asked the sleep took
            prev = now;

            long hb = heartbeat.get();
            if (hb != lastSeenHeartbeat) {
                lastSeenHeartbeat = hb;
                lastHeartbeatChangeMillis = now;
            }
            long sinceBeat = now - lastHeartbeatChangeMillis;
            boolean appStalled = sinceBeat > stallThresholdMillis;
            boolean jvmPaused = overrun > pauseToleranceMillis;

            if ((appStalled || jvmPaused) && !stalled) {
                stalled = true;
                String reason = appStalled
                        ? "APP STALL — heartbeat frozen for " + sinceBeat + "ms (executors wedged: lock/deadlock/pool)"
                        : "JVM PAUSE — watchdog starved " + overrun + "ms over budget (GC pause or swap thrash)";
                dump(reason);
            } else if (!appStalled && !jvmPaused && stalled) {
                stalled = false;
                log.warn("stall watchdog: recovered — the app is making progress again");
            }
        }
    }

    private void dump(String reason) {
        StringBuilder sb = new StringBuilder(16_384);
        Instant ts = Instant.now();
        sb.append("=== JETHRO STALL DUMP ").append(ts).append(" ===\n").append(reason).append("\n\n");

        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        try {
            long[] deadlocked = threads.findDeadlockedThreads();
            if (deadlocked != null && deadlocked.length > 0) {
                sb.append("!!! DEADLOCK — ").append(deadlocked.length).append(" thread(s):\n");
                for (ThreadInfo ti : threads.getThreadInfo(deadlocked, true, true)) {
                    sb.append(ti);
                }
                sb.append('\n');
            } else {
                sb.append("no deadlock detected by the JVM\n\n");
            }
        } catch (Exception e) {
            sb.append("deadlock check failed: ").append(e).append('\n');
        }

        // Thread-state histogram (a wall of BLOCKED points at lock contention; WAITING at a starved pool).
        try {
            ThreadInfo[] all = threads.dumpAllThreads(true, true);
            Map<Thread.State, Integer> hist = new EnumMap<>(Thread.State.class);
            for (ThreadInfo ti : all) {
                hist.merge(ti.getThreadState(), 1, Integer::sum);
            }
            sb.append("threads=").append(all.length).append("  states=").append(hist).append("\n\n");
            appendMemory(sb);
            appendProcAndSwap(sb);
            sb.append("--- native memory (NMT summary) ---\n").append(nmtSummary()).append("\n\n");
            sb.append("--- full thread dump ---\n");
            for (ThreadInfo ti : all) {
                sb.append(ti);
            }
        } catch (Exception e) {
            sb.append("thread dump failed: ").append(e).append('\n');
        }

        String body = sb.toString();
        log.error("STALL DETECTED — {}\n{}", reason, body); // to the app log no matter what
        try {
            Files.createDirectories(dumpDir);
            Path file = dumpDir.resolve("jethro-stall-" + ts.toEpochMilli() + ".txt");
            Files.writeString(file, body);
            log.error("stall dump written to {}", file.toAbsolutePath());
        } catch (Exception e) {
            log.error("could not write stall dump file (logged above instead): {}", e.toString());
        }
    }

    private static void appendMemory(StringBuilder sb) {
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        var heap = mem.getHeapMemoryUsage();
        sb.append("heap used/committed/max MB = ")
                .append(heap.getUsed() >> 20).append('/').append(heap.getCommitted() >> 20)
                .append('/').append(heap.getMax() >> 20).append('\n');
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            sb.append("gc[").append(gc.getName()).append("] count=").append(gc.getCollectionCount())
                    .append(" time=").append(gc.getCollectionTime()).append("ms\n");
        }
        sb.append('\n');
    }

    /** Best-effort Linux memory truth — the swap columns are what confirm/deny "swap is full". */
    private static void appendProcAndSwap(StringBuilder sb) {
        readProc("/proc/self/status", sb, "VmRSS", "VmSwap", "Threads");
        readProc("/proc/meminfo", sb, "MemTotal", "MemAvailable", "SwapTotal", "SwapFree");
        appendZombies(sb);
        sb.append('\n');
    }

    /** Counts defunct (zombie) processes system-wide — a rising count is a fork-without-reap leak that
     *  eats PIDs and can eventually starve the JVM of native threads. Names the top reaping parent. */
    private static void appendZombies(StringBuilder sb) {
        try {
            int zombies = 0;
            long topParent = -1;
            var counts = new java.util.HashMap<Long, Integer>();
            try (var dirs = Files.newDirectoryStream(Path.of("/proc"), p -> {
                String n = p.getFileName().toString();
                return !n.isEmpty() && Character.isDigit(n.charAt(0));
            })) {
                for (Path p : dirs) {
                    try {
                        String stat = Files.readString(p.resolve("stat"));
                        int close = stat.lastIndexOf(')'); // skip comm which may contain spaces/parens
                        String[] f = stat.substring(close + 2).split(" ");
                        if ("Z".equals(f[0])) { // state
                            zombies++;
                            long ppid = Long.parseLong(f[1]);
                            counts.merge(ppid, 1, Integer::sum);
                        }
                    } catch (Exception ignore) {
                        // process vanished between listing and read — normal, skip
                    }
                }
            }
            long best = counts.entrySet().stream().max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey).orElse(-1L);
            topParent = best;
            sb.append("zombies (defunct) = ").append(zombies);
            if (topParent > 0) {
                sb.append("  top non-reaping parent PID = ").append(topParent).append(" ×")
                        .append(counts.get(topParent));
                try {
                    sb.append("  (").append(Files.readString(Path.of("/proc/" + topParent + "/comm")).trim()).append(')');
                } catch (Exception ignore) {
                    // parent gone
                }
            }
            sb.append('\n');
        } catch (Exception ignore) {
            // not Linux / not readable
        }
    }

    /** JVM Native Memory Tracking summary via the diagnostic-command MBean — the category breakdown
     *  (Thread / Class / Code / GC / Internal / Other / direct buffers) that localises a native leak.
     *  Requires {@code -XX:NativeMemoryTracking=summary} at launch; otherwise reports it's off. */
    /** Loaded/unloaded class counts — if loaded climbs while unloaded stays ~0, classes are generated
     *  and never reclaimed (a metaspace/class leak, the NMT "Class"/"Code" growth). */
    private static String classLoadingLine() {
        var cl = ManagementFactory.getClassLoadingMXBean();
        return String.format("classes loaded(total)=%d currently=%d unloaded=%d",
                cl.getTotalLoadedClassCount(), cl.getLoadedClassCount(), cl.getUnloadedClassCount());
    }

    private static String nmtSummary() {
        try {
            var name = new javax.management.ObjectName("com.sun.management:type=DiagnosticCommand");
            Object out = ManagementFactory.getPlatformMBeanServer().invoke(name, "vmNativeMemory",
                    new Object[]{new String[]{"summary"}}, new String[]{String[].class.getName()});
            return String.valueOf(out);
        } catch (Exception e) {
            return "NMT unavailable (" + e.getClass().getSimpleName() + ") — launch with "
                    + "-XX:NativeMemoryTracking=summary to enable";
        }
    }

    private static void readProc(String path, StringBuilder sb, String... keys) {
        try {
            for (String line : Files.readAllLines(Path.of(path))) {
                for (String k : keys) {
                    if (line.startsWith(k + ":")) {
                        sb.append(path).append("  ").append(line.trim()).append('\n');
                    }
                }
            }
        } catch (Exception ignore) {
            // not Linux / not readable — skip; the thread dump still tells most of the story
        }
    }

    @Override
    public void close() {
        if (watcher != null) {
            watcher.interrupt();
        }
        if (beat != null) {
            beat.shutdownNow();
        }
    }
}
