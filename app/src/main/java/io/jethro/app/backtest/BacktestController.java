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
    private final WalkForwardService walkForward;

    public BacktestController(BacktestService service, WalkForwardService walkForward) {
        this.service = service;
        this.walkForward = walkForward;
    }

    /** Walk-forward replay on REAL bars (ADR-0027): params fit per fold, measured OOS. */
    @GetMapping("/api/backtest/walkforward")
    public Object walkForward(@RequestParam(name = "algo", required = false) String algo,
                              @RequestParam(name = "fitDays", required = false) Integer fitDays,
                              @RequestParam(name = "evalDays", required = false) Integer evalDays)
            throws java.io.IOException {
        var result = walkForward.run(algo, fitDays, evalDays);
        if (result.isEmpty()) {
            return java.util.Map.of("note", "no historical bars at " + walkForward.barsPath()
                    + " — run scripts/fetch_bars.py on a networked host first");
        }
        return result.get();
    }

    @GetMapping("/api/backtest")
    public BacktestDto backtest(@RequestParam(name = "seed", required = false) Long seed,
                                @RequestParam(name = "ticks", defaultValue = "20000") int ticks,
                                @RequestParam(name = "regimes", required = false) Boolean regimes,
                                @RequestParam(name = "costBps", required = false) BigDecimal costBps,
                                @RequestParam(name = "algo", required = false) String algo) {
        return toDto(service.run(seed, ticks, regimes, costBps, algo));
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
