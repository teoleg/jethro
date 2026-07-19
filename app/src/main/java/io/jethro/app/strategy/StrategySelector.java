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

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "strategy-selector");
            t.setDaemon(true);
            return t;
        });
        // First run a bit after boot (let the app finish starting; the backtest is CPU-heavy),
        // then on the interval. Kicked off on the pool thread so start() never blocks.
        scheduler.scheduleWithFixedDelay(this::refresh, initialDelaySeconds, intervalMinutes * 60,
                TimeUnit.SECONDS);
        log.info("strategy selector started (ADR-0043): {} OOS seeds × {} ticks per algo, first run in {}s, then every {} min",
                seedCount, ticks, initialDelaySeconds, intervalMinutes);
    }

    private void refresh() {
        long t0 = System.currentTimeMillis();
        try {
            var momentum = backtest.oosByInstrument(ticks, seedCount, "momentum");
            var meanReversion = backtest.oosByInstrument(ticks, seedCount, "mean-reversion");
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
            BigDecimal mPnl = median(m);
            BigDecimal rPnl = median(r);
            boolean mOk = m != null && m.trades() > 0 && mPnl.signum() > 0;
            boolean rOk = r != null && r.trades() > 0 && rPnl.signum() > 0;
            String algo;
            if (mOk && rOk) {
                algo = mPnl.compareTo(rPnl) >= 0 ? "momentum" : "mean-reversion";
            } else if (mOk) {
                algo = "momentum";
            } else if (rOk) {
                algo = "mean-reversion";
            } else {
                algo = SelectingStrategy.NO_TRADE;
            }
            out.put(id, new Choice(algo, mPnl, m != null ? m.trades() : 0,
                    rPnl, r != null ? r.trades() : 0));
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
