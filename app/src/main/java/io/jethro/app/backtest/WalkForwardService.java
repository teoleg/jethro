package io.jethro.app.backtest;

import io.jethro.app.order.ExecutionProperties;
import io.jethro.app.strategy.StrategyProperties;
import io.jethro.trading.riskpnl.InstrumentRef;
import io.jethro.trading.riskpnl.InstrumentRefSource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runs the walk-forward replay (ADR-0027) over the DB {@code daily_close} SEED history — the ONE
 * history source of truth (ADR-0128), Tiingo-seeded and periodically refreshed, the same window that
 * feeds VaR / vol-targeting / the hedge covariance — with the LIVE sizing/cost config, so its verdict
 * measures the same economics on the same data as everything else. Falls back to the on-host bars file
 * ({@code jethro.backtest.bars-path}) only when the DB is unavailable/empty (e.g. persistence off in
 * tests). No history is a normal, honestly-reported state — the platform never fabricates it.
 */
public final class WalkForwardService {

    private final WalkForwardEngine engine = new WalkForwardEngine();
    private final StrategyProperties strategy;
    private final ExecutionProperties execution;
    private final InstrumentRefSource refs;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc; // nullable: DB history source of truth
    private final Path barsPath;

    public WalkForwardService(StrategyProperties strategy, ExecutionProperties execution,
                              InstrumentRefSource refs, org.springframework.jdbc.core.JdbcTemplate jdbc,
                              String barsPath) {
        this.strategy = strategy;
        this.execution = execution;
        this.refs = refs;
        this.jdbc = jdbc;
        this.barsPath = Path.of(barsPath);
    }

    /** @return empty when no history is available yet (no DB rows and no bars file). */
    public Optional<WalkForwardEngine.Result> run(String algo, Integer fitDays, Integer evalDays)
            throws java.io.IOException {
        Map<String, List<HistoricalBars.Bar>> bars = loadBars();
        if (bars.isEmpty()) {
            return Optional.empty();
        }
        Map<String, BigDecimal> multipliers = new LinkedHashMap<>();
        bars.keySet().forEach(id -> multipliers.put(id,
                refs.find(id).map(InstrumentRef::multiplier).orElse(BigDecimal.ONE)));
        String algoOrLive = algo != null && !algo.isBlank() ? algo.trim().toLowerCase()
                : strategy.algoOrDefault();
        var cfg = new WalkForwardEngine.Config(
                algoOrLive,
                fitDays != null && fitDays > 20 ? fitDays : 252,   // ~1y fit
                evalDays != null && evalDays > 5 ? evalDays : 63,  // ~1 quarter eval
                strategy.minSignalBpsOrDefault(),
                strategy.targetNotional(),
                strategy.maxOrderNotionalOrDefault(),
                strategy.maxPositionNotionalOrDefault(),
                strategy.allowShortOrDefault(),
                execution.perFillCostBps("EQUITY"),
                multipliers);
        return Optional.of(engine.run(bars, cfg));
    }

    /** History for the replay: the DB {@code daily_close} SEED window (the source of truth) when the DB
     *  is wired and has rows; otherwise the on-host bars file; otherwise empty (honestly reported). */
    private Map<String, List<HistoricalBars.Bar>> loadBars() throws java.io.IOException {
        if (jdbc != null) {
            Map<String, List<HistoricalBars.Bar>> db =
                    HistoricalBars.fromDailyClose(jdbc, io.jethro.app.risk.DailyCloseSeries.SEED);
            if (!db.isEmpty()) {
                return db;
            }
        }
        return Files.exists(barsPath) ? HistoricalBars.load(barsPath) : Map.of();
    }

    public String barsPath() {
        return barsPath.toString();
    }
}
