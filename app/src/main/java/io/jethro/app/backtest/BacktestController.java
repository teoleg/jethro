package io.jethro.app.backtest;

import io.jethro.app.strategy.StrategyProperties;
import io.jethro.app.trading.TradingCoreProperties;
import io.jethro.trading.riskpnl.InstrumentRef;
import io.jethro.trading.riskpnl.InstrumentRefSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * On-demand backtest of the CURRENT configuration (build step 8): replays the seedable sim
 * tape through the live strategy + average-cost ledger and returns measured PnL. Decimals as
 * strings (invariant 1). Deterministic — the same seed reproduces the run. Backtests only the
 * price-quoted instruments (equity/future/FX); rates/swaps price off the curve and get their
 * own backtest later.
 */
@RestController
public final class BacktestController {

    private static final int MIN_TICKS = 1_000;
    private static final int MAX_TICKS = 200_000;

    public record InstrumentResultDto(String instrumentId, int trades,
                                      String realizedPnl, String unrealizedPnl, String endPosition) {
    }

    public record BacktestDto(long seed, int ticks, int evaluations, int signals, int trades,
                              String realizedPnl, String unrealizedPnl, String totalPnl,
                              String maxDrawdown, double winRate,
                              List<InstrumentResultDto> byInstrument, List<String> equityCurve) {
    }

    private final BacktestEngine engine;
    private final TradingCoreProperties sim;
    private final StrategyProperties strategy;
    private final InstrumentRefSource refs;

    public BacktestController(BacktestEngine engine, TradingCoreProperties sim,
                              StrategyProperties strategy, InstrumentRefSource refs) {
        this.engine = engine;
        this.sim = sim;
        this.strategy = strategy;
        this.refs = refs;
    }

    @GetMapping("/api/backtest")
    public BacktestDto backtest(@RequestParam(name = "seed", required = false) Long seed,
                                @RequestParam(name = "ticks", defaultValue = "20000") int ticks,
                                @RequestParam(name = "regimes", required = false) Boolean regimes) {
        int bounded = Math.max(MIN_TICKS, Math.min(MAX_TICKS, ticks));
        BacktestConfig cfg = new BacktestConfig(
                seed != null ? seed : sim.simSeed(), bounded,
                regimes != null ? regimes : sim.simRegimesOrDefault(),
                evalEveryTicks(),
                strategy.lookback(), strategy.thresholdSigmasOrDefault(), strategy.minSignalBpsOrDefault(),
                strategy.targetNotional(), strategy.maxOrderNotionalOrDefault(),
                strategy.maxPositionNotionalOrDefault(), strategy.allowShortOrDefault(),
                universe());
        return toDto(engine.run(cfg));
    }

    /** Strategy cadence in ticks, from the live interval and tick size (fallback 50 = 5s @ 100ms). */
    private int evalEveryTicks() {
        long tickMillis = sim.simTickIntervalMillis();
        if (tickMillis <= 0) {
            return 50;
        }
        return Math.max(1, (int) (strategy.intervalSeconds() * 1000 / tickMillis));
    }

    /** Price-quoted instruments from the sim universe (equity/future/FX); rates/swaps excluded. */
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

    private static BacktestDto toDto(BacktestResult r) {
        List<InstrumentResultDto> rows = r.byInstrument().stream()
                .map(x -> new InstrumentResultDto(x.instrumentId(), x.trades(),
                        x.realizedPnl().toPlainString(), x.unrealizedPnl().toPlainString(),
                        x.endPosition().toPlainString()))
                .toList();
        List<String> curve = r.equityCurve().stream().map(BigDecimal::toPlainString).toList();
        return new BacktestDto(r.seed(), r.ticks(), r.evaluations(), r.signals(), r.trades(),
                r.realizedPnl().toPlainString(), r.unrealizedPnl().toPlainString(), r.totalPnl().toPlainString(),
                r.maxDrawdown().toPlainString(), r.winRate(), rows, curve);
    }
}
