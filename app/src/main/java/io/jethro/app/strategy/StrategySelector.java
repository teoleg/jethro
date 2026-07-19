package io.jethro.app.strategy;

import io.jethro.app.backtest.BacktestResult;
import io.jethro.app.backtest.BacktestService;
import io.jethro.trading.algo.strategy.SelectingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Per-instrument strategy selector (ADR-0043): periodically measures momentum AND mean-reversion
 * out-of-sample (the ADR-0027 multi-seed, cost-honest harness) and, per instrument, chooses the
 * algo with the higher median net PnL — requiring that median to be positive over a majority of
 * paths, else {@link SelectingStrategy#NO_TRADE}. The live {@link SelectingStrategy} reads
 * {@link #algoFor} each cycle; an instrument not yet measured returns {@code null} so it falls back
 * to the configured default. Runs on a background thread (backtests are CPU work, never on the boot
 * path or a trading cadence); a run that fails leaves the last good selection in place.
 */
public final class StrategySelector implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(StrategySelector.class);

    /** Per-instrument verdict: the chosen algo and the two measured medians behind the call. */
    public record Choice(String algo, BigDecimal momentumMedianPnl, int momentumTrades,
                         BigDecimal meanReversionMedianPnl, int meanReversionTrades) {
    }

    private final BacktestService backtest;
    private final int seedCount;
    private final int ticks;
    private final long intervalMinutes;
    private final long initialDelaySeconds;

    private volatile Map<String, Choice> choices = Map.of();
    private volatile long lastRunMillis;
    private volatile String lastError; // surfaced in the API/export so a dead run is visible
    private ScheduledExecutorService scheduler;

    public StrategySelector(BacktestService backtest, int seedCount, int ticks, long intervalMinutes,
                            long initialDelaySeconds) {
        this.backtest = backtest;
        this.seedCount = Math.max(3, seedCount | 1); // odd ≥3 so a median is a strict majority
        this.ticks = ticks;
        this.intervalMinutes = Math.max(1, intervalMinutes);
        this.initialDelaySeconds = Math.max(5, initialDelaySeconds);
    }

    /** Yield the core this long between individual OOS backtest runs so the CPU-bound refresh
     *  doesn't monopolise a small/single-core box and starve the REST threads (which made the
     *  UI's polling pile up). My own value, not a risk/money dial — purely a scheduling knob. */
    private static final long INTER_RUN_PAUSE_MILLIS = 25;

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "strategy-selector");
            t.setDaemon(true);
            // Below-normal priority: the refresh is a background chore, never latency-critical.
            // Lets the OS favour request-serving threads when they're runnable on a shared core.
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        // First run a bit after boot (let the app finish starting; the backtest is CPU-heavy),
        // then on the interval. Kicked off on the pool thread so start() never blocks.
        scheduler.scheduleWithFixedDelay(this::refresh, initialDelaySeconds, intervalMinutes * 60,
                TimeUnit.SECONDS);
        log.info("strategy selector started (ADR-0043): {} OOS seeds × {} ticks per algo, first run in {}s, then every {} min",
                seedCount, ticks, initialDelaySeconds, intervalMinutes);
    }

    /** Skip a refresh unless at least this fraction of the max heap is free. The hourly OOS run is a
     *  large TRANSIENT allocation spike (seedCount × 2 algos × ticks × instruments of BigDecimal
     *  math); on a small heap (dev default 512m) that spike drove ZGC into allocation stalls that
     *  froze EVERY thread — the whole server appeared wedged for the run's duration. Refusing to
     *  start the spike when headroom is thin trades a stale selection (we keep the last one) for a
     *  live server. My own operational guard, not a risk/money dial. Give the box more heap (≥1g) to
     *  keep the selector refreshing; the guard is the safety net, not the intended steady state. */
    private static final double MIN_FREE_HEAP_FRACTION = 0.35;

    /** True when free heap headroom is below the guard — running the backtest spike would risk a
     *  stall/OOM. Uses committed-vs-max so a not-yet-grown heap still counts its uncommitted room. */
    private static boolean lowHeap() {
        Runtime rt = Runtime.getRuntime();
        return lowHeap(rt.maxMemory(), rt.totalMemory() - rt.freeMemory(), MIN_FREE_HEAP_FRACTION);
    }

    /** Pure guard predicate (package-visible for exact-value testing): headroom = max − used; low
     *  when that is under {@code minFreeFraction} of max. */
    static boolean lowHeap(long maxBytes, long usedBytes, double minFreeFraction) {
        long headroom = maxBytes - usedBytes;    // bytes the heap can still hand out before OOM
        return headroom < maxBytes * minFreeFraction;
    }

    private void refresh() {
        long t0 = System.currentTimeMillis();
        if (lowHeap()) {
            Runtime rt = Runtime.getRuntime();
            long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
            long maxMb = rt.maxMemory() / (1024 * 1024);
            log.warn("skipping OOS selection refresh — heap headroom below {}% ({}MB used of {}MB max). "
                    + "Keeping the last selection; give the JVM more heap (≥1g) so the hourly backtest can run "
                    + "without risking an allocation stall.", (int) (MIN_FREE_HEAP_FRACTION * 100), usedMb, maxMb);
            return;
        }
        try {
            var momentum = backtest.oosByInstrument(ticks, seedCount, "momentum", INTER_RUN_PAUSE_MILLIS);
            var meanReversion = backtest.oosByInstrument(ticks, seedCount, "mean-reversion", INTER_RUN_PAUSE_MILLIS);
            Map<String, Choice> next = choose(momentum, meanReversion);
            choices = next;
            lastRunMillis = System.currentTimeMillis();
            lastError = null;
            long traded = next.values().stream().filter(c -> !SelectingStrategy.NO_TRADE.equals(c.algo())).count();
            log.info("strategy selection refreshed in {}ms: {} instruments, {} tradable, {} no-edge — {}",
                    lastRunMillis - t0, next.size(), traded, next.size() - traded, summarize(next));
        } catch (Throwable t) {
            lastError = t.toString();
            // Catch THROWABLE, not just Exception: an Error (e.g. OOM on a small box) escaping here
            // would cancel the scheduled task for good — the selector would silently never run again.
            // Swallow it, keep the last selection, and stay scheduled.
            log.warn("strategy selection refresh failed after {}ms ({}) — keeping the last selection; "
                    + "the strategy runs the default algo meanwhile", System.currentTimeMillis() - t0, t.toString());
        }
    }

    /**
     * Pure per-instrument choice: the algo with the higher median net PnL, requiring a positive
     * median over positive trade count (a majority of OOS paths profitable). Neither qualifying →
     * NO_TRADE. Package-visible and static for exact-value testing.
     */
    static Map<String, Choice> choose(Map<String, BacktestResult.InstrumentResult> momentum,
                                      Map<String, BacktestResult.InstrumentResult> meanReversion) {
        Set<String> ids = new LinkedHashSet<>();
        ids.addAll(momentum.keySet());
        ids.addAll(meanReversion.keySet());
        Map<String, Choice> out = new LinkedHashMap<>();
        for (String id : ids) {
            BacktestResult.InstrumentResult m = momentum.get(id);
            BacktestResult.InstrumentResult r = meanReversion.get(id);
            int momTrades = m != null ? m.trades() : 0;
            int mrTrades = r != null ? r.trades() : 0;
            // NO MEASUREMENT: if neither algo traded in the backtest, we have not measured this
            // name's edge — that is NOT "no edge". Leave it OUT of the map so the live strategy
            // runs the DEFAULT algo and keeps trading, while later/longer runs accumulate real
            // data. Only a name that DID trade and still lost on both algos is a true NO-TRADE.
            if (momTrades == 0 && mrTrades == 0) {
                continue;
            }
            BigDecimal mPnl = median(m);
            BigDecimal rPnl = median(r);
            boolean mOk = momTrades > 0 && mPnl.signum() > 0;
            boolean rOk = mrTrades > 0 && rPnl.signum() > 0;
            String algo;
            if (mOk && rOk) {
                algo = mPnl.compareTo(rPnl) >= 0 ? "momentum" : "mean-reversion";
            } else if (mOk) {
                algo = "momentum";
            } else if (rOk) {
                algo = "mean-reversion";
            } else {
                algo = SelectingStrategy.NO_TRADE; // measured (traded) but neither positive
            }
            out.put(id, new Choice(algo, mPnl, momTrades, rPnl, mrTrades));
        }
        return out;
    }

    private static BigDecimal median(BacktestResult.InstrumentResult ir) {
        return ir == null ? BigDecimal.ZERO : ir.realizedPnl().add(ir.unrealizedPnl());
    }

    private static String summarize(Map<String, Choice> choices) {
        StringBuilder sb = new StringBuilder();
        choices.forEach((id, c) -> sb.append(id).append("→").append(c.algo()).append(' '));
        return sb.toString().trim();
    }

    /** The algo selected for an instrument, or {@code null} if not yet measured (caller defaults). */
    public String algoFor(String instrumentId) {
        Choice c = choices.get(instrumentId);
        return c == null ? null : c.algo();
    }

    /**
     * ADR-0044 edge gate. Given the price-derived detector's trend-matched candidate algo, VETO it to
     * {@link SelectingStrategy#NO_TRADE} only when THAT algo has a MEASURED non-positive OOS median
     * (trades &gt; 0 and median ≤ 0). Unmeasured or measured-positive → allow the candidate through.
     * The detector decides which algo and when to switch; this only blocks trading into a measured
     * loser (a detector false-positive can't force a losing trade). Deliberately conservative: the
     * OOS median is regime-averaged, so a genuinely trend-profitable algo whose all-regime median is
     * negative is still vetoed — the regime-specific validator is the ADR-0044 walk-forward follow-up.
     */
    public String gate(String instrumentId, String candidateAlgo) {
        Choice c = choices.get(instrumentId);
        if (c == null || candidateAlgo == null) {
            return candidateAlgo; // unmeasured → trust the detector, nothing to veto on
        }
        BigDecimal median;
        int trades;
        if ("momentum".equals(candidateAlgo)) {
            median = c.momentumMedianPnl();
            trades = c.momentumTrades();
        } else if ("mean-reversion".equals(candidateAlgo)) {
            median = c.meanReversionMedianPnl();
            trades = c.meanReversionTrades();
        } else {
            return candidateAlgo; // default/unknown algo — no per-algo measurement to gate on
        }
        if (trades > 0 && median != null && median.signum() <= 0) {
            return SelectingStrategy.NO_TRADE;
        }
        return candidateAlgo;
    }

    /** The current selection (for the UI/API); empty until the first run completes. */
    public Map<String, Choice> selection() {
        return choices;
    }

    public long lastRunMillis() {
        return lastRunMillis;
    }

    /** The last refresh error (e.g. an OOM), or null if the last run succeeded / none yet. */
    public String lastError() {
        return lastError;
    }

    @Override
    public void close() {
        ScheduledExecutorService s = scheduler;
        if (s != null) {
            s.shutdownNow();
            scheduler = null;
        }
    }
}
