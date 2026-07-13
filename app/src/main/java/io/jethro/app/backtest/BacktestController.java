package io.jethro.app.backtest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * On-demand backtest of the CURRENT configuration (build step 8): delegates to
 * {@link BacktestService}, which replays the seedable sim tape through the live strategy +
 * average-cost ledger. Decimals as strings (invariant 1). Deterministic — same seed reproduces
 * the run. seed/ticks/regimes/costBps are overridable.
 */
@RestController
public final class BacktestController {

    public record InstrumentResultDto(String instrumentId, int trades,
                                      String realizedPnl, String unrealizedPnl, String endPosition) {
    }

    public record BacktestDto(long seed, int ticks, int evaluations, int signals, int trades,
                              String realizedPnl, String unrealizedPnl, String totalPnl,
                              String maxDrawdown, double winRate, String totalCosts,
                              List<InstrumentResultDto> byInstrument, List<String> equityCurve) {
    }

    private final BacktestService service;

    public BacktestController(BacktestService service) {
        this.service = service;
    }

    @GetMapping("/api/backtest")
    public BacktestDto backtest(@RequestParam(name = "seed", required = false) Long seed,
                                @RequestParam(name = "ticks", defaultValue = "20000") int ticks,
                                @RequestParam(name = "regimes", required = false) Boolean regimes,
                                @RequestParam(name = "costBps", required = false) BigDecimal costBps) {
        return toDto(service.run(seed, ticks, regimes, costBps));
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
                r.maxDrawdown().toPlainString(), r.winRate(), r.totalCosts().toPlainString(), rows, curve);
    }
}
