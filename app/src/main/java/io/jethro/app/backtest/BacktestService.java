package io.jethro.app.backtest;

import io.jethro.app.strategy.StrategyProperties;
import io.jethro.app.trading.TradingCoreProperties;
import io.jethro.trading.riskpnl.InstrumentRef;
import io.jethro.trading.riskpnl.InstrumentRefSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds a {@link BacktestConfig} from the CURRENT live configuration and runs it — the one
 * place that turns the running strategy/sim settings into a reproducible backtest. Shared by
 * the REST surface and the hypothesis layer (which backtests each thesis's instrument before
 * surfacing it). Only the price-quoted universe (equity/future/FX) runs here; rates/swaps
 * price off the curve and get their own backtest later.
 */
public final class BacktestService {

    /** Fallback per-fill cost when no execution config is wired (ADR-0025 default derivation
     *  is EQUITY half-spread + fee = 3.5bp; this constant only backs standalone tests). */
    public static final BigDecimal DEFAULT_COST_BPS = new BigDecimal("3.5");
    private static final int MIN_TICKS = 1_000;
    private static final int MAX_TICKS = 200_000;

    private final BacktestEngine engine;
    private final TradingCoreProperties sim;
    private final StrategyProperties strategy;
    private final InstrumentRefSource refs;
    private final BigDecimal defaultCostBps;

    public BacktestService(BacktestEngine engine, TradingCoreProperties sim,
                           StrategyProperties strategy, InstrumentRefSource refs) {
        this(engine, sim, strategy, refs, DEFAULT_COST_BPS);
    }

    /** @param defaultCostBps per-fill cost derived from the live execution config (ADR-0025:
     *                        half-spread + fee), so backtest and sim P&L measure the same economics. */
    public BacktestService(BacktestEngine engine, TradingCoreProperties sim,
                           StrategyProperties strategy, InstrumentRefSource refs, BigDecimal defaultCostBps) {
        this.engine = engine;
        this.sim = sim;
        this.strategy = strategy;
        this.refs = refs;
        this.defaultCostBps = defaultCostBps;
    }

    /** Runs a backtest of the live config; nulls fall back to the live/default values. */
    public BacktestResult run(Long seed, int ticks, Boolean regimes, BigDecimal costBps) {
        int bounded = Math.max(MIN_TICKS, Math.min(MAX_TICKS, ticks));
        BacktestConfig cfg = new BacktestConfig(
                seed != null ? seed : sim.simSeed(), bounded,
                regimes != null ? regimes : sim.simRegimesOrDefault(),
                evalEveryTicks(),
                strategy.lookback(), strategy.thresholdSigmasOrDefault(), strategy.minSignalBpsOrDefault(),
                strategy.targetNotional(), strategy.maxOrderNotionalOrDefault(),
                strategy.maxPositionNotionalOrDefault(), strategy.allowShortOrDefault(),
                strategy.regimeVolatileScaleOrDefault(),
                costBps != null ? costBps : defaultCostBps,
                universe());
        return engine.run(cfg);
    }

    /**
     * Out-of-sample edge measurement (ADR-0027): runs the backtest on {@code seedCount} seeds
     * DISJOINT from the live sim seed and aggregates per instrument by MEDIAN — so with an odd
     * K, median PnL &gt; 0 ⟺ the strategy was net-positive on a majority of independent paths.
     * This replaces the old single-run-on-the-live-seed gate, which was in-sample
     * self-confirmation (profitable on the exact tape the live sim replays proves nothing).
     */
    public Map<String, BacktestResult.InstrumentResult> oosByInstrument(int ticks, int seedCount) {
        List<BacktestResult> runs = new ArrayList<>(seedCount);
        for (long seed : oosSeeds(sim.simSeed(), seedCount)) {
            runs.add(run(seed, ticks, null, null));
        }
        return aggregateByMedian(runs);
    }

    /** Deterministic seeds derived from (but never equal to) the live seed. Package-visible for tests. */
    static long[] oosSeeds(long liveSeed, int count) {
        long[] seeds = new long[count];
        for (int k = 0; k < count; k++) {
            long candidate = liveSeed ^ (0x9E3779B97F4A7C15L * (k + 1)); // golden-ratio stride
            seeds[k] = candidate == liveSeed ? candidate + 1 : candidate;
        }
        return seeds;
    }

    /**
     * Median-aggregates per-instrument results across runs: median PnL and median trade count.
     * Median (not mean/sum) so one lucky path can't carry the verdict — the downstream
     * "supports" check (pnl &gt; 0 AND trades &gt; 0) then means "a majority of paths agree".
     * Package-visible for exact-value tests.
     */
    static Map<String, BacktestResult.InstrumentResult> aggregateByMedian(List<BacktestResult> runs) {
        Map<String, List<BacktestResult.InstrumentResult>> byInstrument = new LinkedHashMap<>();
        for (BacktestResult run : runs) {
            for (BacktestResult.InstrumentResult ir : run.byInstrument()) {
                byInstrument.computeIfAbsent(ir.instrumentId(), k -> new ArrayList<>()).add(ir);
            }
        }
        Map<String, BacktestResult.InstrumentResult> out = new LinkedHashMap<>();
        byInstrument.forEach((id, results) -> {
            List<BigDecimal> pnls = results.stream()
                    .map(r -> r.realizedPnl().add(r.unrealizedPnl())).sorted().toList();
            List<Integer> trades = results.stream().map(BacktestResult.InstrumentResult::trades)
                    .sorted().toList();
            // Median PnL is reported entirely as "realized" in the aggregate (the split is
            // per-path detail; the gate only reads the sum and the trade count).
            out.put(id, new BacktestResult.InstrumentResult(id,
                    trades.get(trades.size() / 2),
                    pnls.get(pnls.size() / 2),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO));
        });
        return out;
    }

    /** Strategy cadence in ticks, from the live interval and tick size (fallback 50 = 5s @ 100ms). */
    private int evalEveryTicks() {
        long tickMillis = sim.simTickIntervalMillis();
        if (tickMillis <= 0) {
            return 50;
        }
        return Math.max(1, (int) (strategy.intervalSeconds() * 1000 / tickMillis));
    }

    private List<BacktestConfig.Instrument> universe() {
        List<BacktestConfig.Instrument> out = new ArrayList<>();
        for (String id : sim.simInstruments()) {
            Optional<InstrumentRef> ref = refs.find(id);
            if (ref.isEmpty()) {
                continue;
            }
            String assetClass = ref.get().assetClass();
            if ("EQUITY".equals(assetClass) || "FUTURE".equals(assetClass) || "FX".equals(assetClass)) {
                out.add(new BacktestConfig.Instrument(id, sim.startPriceFor(id),
                        sim.annualVolFor(id), ref.get().multiplier()));
            }
        }
        return out;
    }
}
