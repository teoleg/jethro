package io.jethro.app.backtest;

import io.jethro.app.strategy.StrategyProperties;
import io.jethro.app.trading.TradingCoreProperties;
import io.jethro.trading.riskpnl.InstrumentRef;
import io.jethro.trading.riskpnl.InstrumentRefSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Builds a {@link BacktestConfig} from the CURRENT live configuration and runs it — the one
 * place that turns the running strategy/sim settings into a reproducible backtest. Shared by
 * the REST surface and the hypothesis layer (which backtests each thesis's instrument before
 * surfacing it). Only the price-quoted universe (equity/future/FX) runs here; rates/swaps
 * price off the curve and get their own backtest later.
 */
public final class BacktestService {

    /** Default transaction cost applied to a backtest when the caller doesn't specify one. */
    public static final BigDecimal DEFAULT_COST_BPS = new BigDecimal("1.0");
    private static final int MIN_TICKS = 1_000;
    private static final int MAX_TICKS = 200_000;

    private final BacktestEngine engine;
    private final TradingCoreProperties sim;
    private final StrategyProperties strategy;
    private final InstrumentRefSource refs;

    public BacktestService(BacktestEngine engine, TradingCoreProperties sim,
                           StrategyProperties strategy, InstrumentRefSource refs) {
        this.engine = engine;
        this.sim = sim;
        this.strategy = strategy;
        this.refs = refs;
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
                costBps != null ? costBps : DEFAULT_COST_BPS,
                universe());
        return engine.run(cfg);
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
