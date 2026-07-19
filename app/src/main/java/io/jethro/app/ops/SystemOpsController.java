package io.jethro.app.ops;

import io.jethro.app.strategy.StrategySelector;
import io.jethro.trading.algo.strategy.SelectingStrategy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;

/**
 * JVM runtime metrics for the Ops screen (ADR-0013 dev-cost posture — a small box needs to be
 * watchable). Surfaces the heap picture behind the ~1h freeze: how close the process runs to its
 * -Xmx ceiling and to the OOS-selector heap guard that skips the hourly backtest when headroom is
 * thin. Read-only, cheap (JMX beans), no feed-mode gating.
 */
@RestController
public final class SystemOpsController {

    private static final long MB = 1024 * 1024;

    private final ObjectProvider<StrategySelector> selector;

    public SystemOpsController(ObjectProvider<StrategySelector> selector) {
        this.selector = selector;
    }

    public record SelectorStatus(boolean present, long lastRunMillis, String lastError,
                                 int measured, int tradable, boolean guardTripping) {
    }

    public record JvmMetrics(long heapUsedMb, long heapCommittedMb, long heapMaxMb,
                             int heapUsedPct, int headroomPct, int guardPct, boolean guardTripping,
                             long nonHeapUsedMb, int threads, long gcCount, long gcTimeMs,
                             long uptimeSeconds, SelectorStatus selector) {
    }

    @GetMapping("/api/ops/jvm")
    public JvmMetrics jvm() {
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = mem.getHeapMemoryUsage();
        long used = heap.getUsed();
        long committed = heap.getCommitted();
        // max can be -1 (undefined) for some collectors — fall back to the Runtime ceiling.
        long max = heap.getMax() > 0 ? heap.getMax() : Runtime.getRuntime().maxMemory();
        long nonHeap = mem.getNonHeapMemoryUsage().getUsed();

        int usedPct = max > 0 ? (int) Math.round(100.0 * used / max) : 0;
        int headroomPct = Math.max(0, 100 - usedPct);
        int guardPct = (int) Math.round(StrategySelector.MIN_FREE_HEAP_FRACTION * 100);
        boolean guardTripping = headroomPct < guardPct;

        long gcCount = 0;
        long gcTime = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gc.getCollectionCount() > 0) {
                gcCount += gc.getCollectionCount();
            }
            if (gc.getCollectionTime() > 0) {
                gcTime += gc.getCollectionTime();
            }
        }
        int threads = ManagementFactory.getThreadMXBean().getThreadCount();
        long uptime = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;

        StrategySelector sel = selector.getIfAvailable();
        SelectorStatus selStatus;
        if (sel == null) {
            selStatus = new SelectorStatus(false, 0, null, 0, 0, guardTripping);
        } else {
            var selection = sel.selection();
            int tradable = (int) selection.values().stream()
                    .filter(c -> !SelectingStrategy.NO_TRADE.equals(c.algo())).count();
            selStatus = new SelectorStatus(true, sel.lastRunMillis(), sel.lastError(),
                    selection.size(), tradable, guardTripping);
        }

        return new JvmMetrics(used / MB, committed / MB, max / MB, usedPct, headroomPct, guardPct,
                guardTripping, nonHeap / MB, threads, gcCount, gcTime, uptime, selStatus);
    }
}
