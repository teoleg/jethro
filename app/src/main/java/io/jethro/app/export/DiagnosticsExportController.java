package io.jethro.app.export;

import io.jethro.trading.riskpnl.ConsolidatedRisk;
import io.jethro.trading.riskpnl.PositionRisk;
import io.jethro.trading.riskpnl.RiskProjection;
import io.jethro.app.risk.VarService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * One-click diagnostics export (the strategy post-mortem workbook): a single {@code .xlsx} with a
 * sheet per view — firm summary, P&L by book and asset class, open positions, recent fills with
 * fees, TCA slippage, AI-hypothesis outcomes, the hedge-order timeline (so a hedge runaway is
 * visible at a glance), and the live strategy tuning params + their audited change history (ADR-0052,
 * so a dial change lines up against outcomes). Built with the dependency-free {@link Xlsx} writer. Read-only; DB sheets
 * are row-capped so the file stays attachable. Persistence-gated: without a datasource the
 * DB-backed sheets are simply omitted (the live risk sheets still export).
 */
@RestController
public final class DiagnosticsExportController {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int ROW_CAP = 20_000;

    private final ObjectProvider<RiskProjection> projection;
    private final ObjectProvider<VarService> varService;
    private final ObjectProvider<JdbcTemplate> jdbc;
    private final ObjectProvider<io.jethro.app.strategy.StrategySelector> selector;
    private final ObjectProvider<io.jethro.trading.algo.strategy.Strategy> tradingStrategy;
    private final ObjectProvider<io.jethro.app.strategy.StrategyControl> strategyControl;
    private final ObjectProvider<io.jethro.app.training.LearnedSignalService> learnedSignal;

    public DiagnosticsExportController(ObjectProvider<RiskProjection> projection,
                                       ObjectProvider<VarService> varService,
                                       ObjectProvider<JdbcTemplate> jdbc,
                                       ObjectProvider<io.jethro.app.strategy.StrategySelector> selector,
                                       ObjectProvider<io.jethro.trading.algo.strategy.Strategy> tradingStrategy,
                                       ObjectProvider<io.jethro.app.strategy.StrategyControl> strategyControl,
                                       ObjectProvider<io.jethro.app.training.LearnedSignalService> learnedSignal) {
        this.projection = projection;
        this.varService = varService;
        this.jdbc = jdbc;
        this.selector = selector;
        this.tradingStrategy = tradingStrategy;
        this.strategyControl = strategyControl;
        this.learnedSignal = learnedSignal;
    }

    @GetMapping("/api/export/diagnostics.xlsx")
    public ResponseEntity<byte[]> diagnostics() {
        Xlsx wb = new Xlsx();
        RiskProjection proj = projection.getIfAvailable();
        ConsolidatedRisk snap = proj != null ? proj.snapshot(System.currentTimeMillis()) : null;
        VarService vs = varService.getIfAvailable();

        summarySheet(wb, snap, vs);
        if (snap != null) {
            groupSheet(wb, "P&L by book", snap.byBook());
            groupSheet(wb, "P&L by asset class", snap.byAssetClass());
            positionsSheet(wb, snap.positions());
        }
        selectionSheet(wb);
        strategyParamsSheet(wb);
        learnedSignalSheet(wb);
        JdbcTemplate db = jdbc.getIfAvailable();
        if (db != null) {
            fillsSheet(db, wb);
            tcaSheet(db, wb);
            hypothesesSheet(db, wb);
            hedgeOrdersSheet(db, wb);
            strategyChangesSheet(db, wb);
        }

        String name = "jethro-diagnostics-" + LocalDateTime.now().format(STAMP) + ".xlsx";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDispositionFormData("attachment", name);
        headers.setContentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        return new ResponseEntity<>(wb.toBytes(), headers, org.springframework.http.HttpStatus.OK);
    }

    private void summarySheet(Xlsx wb, ConsolidatedRisk snap, VarService vs) {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(row("generated", LocalDateTime.now().toString()));
        if (snap != null) {
            ConsolidatedRisk.Totals t = snap.total();
            rows.add(row("clean P&L (USD)", t.totalPnl()));
            rows.add(row("comprehensive P&L (USD)", t.comprehensivePnl()));
            rows.add(row("realized P&L (USD)", t.realizedPnl()));
            rows.add(row("unrealized P&L (USD)", t.unrealizedPnl()));
            rows.add(row("FX translation P&L (USD)", t.fxTranslationPnl()));
            rows.add(row("gross exposure (USD)", t.grossExposure()));
            rows.add(row("net exposure (USD)", t.netExposure()));
            rows.add(row("open position rows", (long) snap.positions().size()));
        }
        if (vs != null) {
            try {
                var var = vs.compute();
                rows.add(row("VaR95 1d (USD)", var.var95()));
                rows.add(row("ES95 1d (USD)", var.es95()));
                rows.add(row("VaR99 1d (USD)", var.var99()));
                rows.add(row("VaR observations (days)", (long) var.observations()));
                if (var.note() != null) {
                    rows.add(row("VaR note", var.note()));
                }
            } catch (RuntimeException e) {
                rows.add(row("VaR", "unavailable: " + e.getMessage()));
            }
        }
        wb.sheet("Summary", List.of("metric", "value"), rows);
    }

    /** Per-instrument selection state — so "no picks / no backtest" is diagnosable from the workbook
     *  alone. Shows the ADR-0043 OOS medians AND (when the ADR-0044 regime path is live) the
     *  price-derived regime + efficiency ratio actually driving the pick, plus market breadth. */
    private void selectionSheet(Xlsx wb) {
        io.jethro.app.strategy.StrategySelector s = selector.getIfAvailable();
        io.jethro.trading.algo.strategy.TrendDetector detector = detector();
        List<List<Object>> rows = new ArrayList<>();
        if (s == null) {
            rows.add(List.of("(selection off — jethro.strategy.selection.enabled=false; single algo runs)"));
        } else if (s.lastError() != null) {
            rows.add(List.of("last measurement FAILED: " + s.lastError()));
        } else if (s.lastRunMillis() == 0) {
            rows.add(List.of("measuring… first OOS run runs ~90s after boot"));
        } else {
            rows.add(List.of("measured at " + java.time.Instant.ofEpochMilli(s.lastRunMillis())
                    + (detector != null ? " · market breadth " + detector.breadth().name() : "")));
            s.selection().forEach((id, c) -> {
                String regime = detector == null ? "—" : detector.regimeFor(id).name();
                java.math.BigDecimal er = detector == null ? null : detector.efficiencyRatio(id);
                rows.add(List.of(id, c.algo(), regime, er == null ? "—" : num(er),
                        num(c.momentumMedianPnl()), (long) c.momentumTrades(),
                        num(c.meanReversionMedianPnl()), (long) c.meanReversionTrades()));
            });
        }
        wb.sheet("Strategy selection", List.of("instrument", "chosen", "live_regime", "efficiency_ratio",
                "momentum_median", "momentum_trades", "meanrev_median", "meanrev_trades"), rows);
    }

    /** The effective strategy dials at export time (ADR-0052): config default vs live value, and
     *  whether each is overridden — so any analysis reading this workbook knows what the strategy was
     *  actually configured with, not just what the file says. Pairs with the "Strategy changes" sheet. */
    private void strategyParamsSheet(Xlsx wb) {
        io.jethro.app.strategy.StrategyControl ctl = strategyControl.getIfAvailable();
        List<List<Object>> rows = new ArrayList<>();
        if (ctl == null) {
            rows.add(List.of("(strategy control unavailable — jethro.strategy.enabled=false)"));
        } else {
            for (var d : ctl.state()) {
                rows.add(List.of(d.key(), d.group(), d.defaultValue(), d.effective(),
                        d.overridden() ? "Y" : "N", d.gatesRisk() ? "Y" : "N"));
            }
        }
        wb.sheet("Strategy params", List.of("dial", "group", "config_default", "effective",
                "overridden", "gates_risk"), rows);
    }

    /** The ADR-0053 learned-signal gate verdict: the model's cost-aware OOS edge vs the coin-flip and
     *  momentum/mean-reversion baselines it must beat to ship. Advisory only — the numbers here are
     *  net-of-cost basis points per opportunity, never a size/PnL/risk input (ADR-0016 / invariant 7). */
    private void learnedSignalSheet(Xlsx wb) {
        io.jethro.app.training.LearnedSignalService svc = learnedSignal.getIfAvailable();
        List<List<Object>> rows = new ArrayList<>();
        if (svc == null) {
            rows.add(List.of("(learned signal off — jethro.training.enabled=false)"));
        } else {
            var res = svc.current();
            var r = res.result();
            if (r == null || !r.sufficient()) {
                rows.add(List.of(r == null ? "no run yet (" + str(res.state()) + ")" : str(r.verdict())));
            } else {
                rows.add(List.of("verdict", r.ships() ? "SHIPS" : "VETOED", str(r.verdict()), "", "", ""));
                rows.add(List.of("cost_bps", num(java.math.BigDecimal.valueOf(r.costBps())),
                        "folds " + r.trainableFolds() + "/" + r.folds(), "oos_rows " + r.testRows(), "", ""));
                rows.add(List.of("", "", "", "", "", ""));
                rows.add(List.of("strategy", "net_bps_per_opp", "trades", "wins", "hit_rate", "coverage"));
                addScore(rows, "learned", r.learned());
                addScore(rows, "coin_flip", r.coinFlip());
                addScore(rows, "momentum", r.momentum());
                addScore(rows, "mean_reversion", r.meanReversion());
            }
        }
        wb.sheet("Learned signal", List.of("row", "a", "b", "c", "d", "e"), rows);
    }

    private void addScore(List<List<Object>> rows, String name, io.jethro.app.training.WalkForwardBacktest.Score s) {
        rows.add(List.of(name, num(java.math.BigDecimal.valueOf(s.netReturnBps())), (long) s.trades(),
                (long) s.wins(), num(java.math.BigDecimal.valueOf(s.hitRate())),
                num(java.math.BigDecimal.valueOf(s.coverage()))));
    }

    /** The audited history of live tuning changes (ADR-0052): old→new, who, when — so a threshold or
     *  sizing change can be lined up against the Fills / P&L sheets to see how the book responded. */
    private void strategyChangesSheet(JdbcTemplate db, Xlsx wb) {
        List<List<Object>> rows = query(db,
                "select changed_at, param, old_value, new_value, actor, note "
                        + "from strategy_param_change order by changed_at desc limit " + ROW_CAP,
                rs -> List.of(str(rs.getObject("changed_at")), str(rs.getString("param")),
                        str(rs.getString("old_value")), str(rs.getString("new_value")),
                        str(rs.getString("actor")), str(rs.getString("note"))));
        wb.sheet("Strategy changes", List.of("changed_at", "param", "old_value", "new_value",
                "actor", "note"), rows);
    }

    /** The live strategy's trend detector when the ADR-0044 regime-aware path is wired, else null. */
    private io.jethro.trading.algo.strategy.TrendDetector detector() {
        io.jethro.trading.algo.strategy.Strategy strategy = tradingStrategy.getIfAvailable();
        return strategy instanceof io.jethro.trading.algo.strategy.SelectingStrategy ss ? ss.detector() : null;
    }

    private void groupSheet(Xlsx wb, String name, List<ConsolidatedRisk.Group> groups) {
        List<List<Object>> rows = new ArrayList<>();
        for (ConsolidatedRisk.Group g : groups) {
            rows.add(List.of(g.key(), g.currency(), num(g.realizedPnl()), num(g.unrealizedPnl()),
                    num(g.totalPnl()), num(g.fxTranslationPnl()), num(g.comprehensivePnl()),
                    num(g.grossExposure()), num(g.netExposure()), (long) g.positionCount()));
        }
        wb.sheet(name, List.of("key", "ccy", "realized", "unrealized", "clean_total",
                "fx_translation", "comprehensive", "gross", "net", "positions"), rows);
    }

    private void positionsSheet(Xlsx wb, List<PositionRisk> positions) {
        List<List<Object>> rows = new ArrayList<>();
        for (PositionRisk p : positions) {
            rows.add(List.of(p.bookId(), p.instrumentId(), p.assetClass(), p.currency(),
                    num(p.quantity()), num(p.avgCost()), num(p.mark()), p.hasMark() ? "Y" : "N",
                    num(p.realizedPnl()), num(p.unrealizedPnl()), num(p.netExposure()),
                    num(p.grossExposure())));
        }
        wb.sheet("Positions", List.of("book", "instrument", "class", "ccy", "qty", "avg_cost",
                "mark", "has_mark", "realized", "unrealized", "net_exposure", "gross_exposure"), rows);
    }

    private void fillsSheet(JdbcTemplate db, Xlsx wb) {
        List<List<Object>> rows = query(db,
                "select executed_at, book_id, instrument_id, side, quantity, price, fee "
                        + "from fills order by executed_at desc limit " + ROW_CAP,
                rs -> List.of(str(rs.getObject("executed_at")), str(rs.getString("book_id")),
                        str(rs.getString("instrument_id")), str(rs.getString("side")),
                        num(rs.getBigDecimal("quantity")), num(rs.getBigDecimal("price")),
                        num(rs.getBigDecimal("fee"))));
        wb.sheet("Fills", List.of("executed_at", "book", "instrument", "side", "qty", "price", "fee"), rows);
    }

    private void tcaSheet(JdbcTemplate db, Xlsx wb) {
        List<List<Object>> rows = query(db,
                "select filled_at, instrument, side, quantity, arrival_price, fill_price, "
                        + "slippage_bps, rate_quoted from execution_quality order by filled_at desc limit " + ROW_CAP,
                rs -> List.of(str(rs.getObject("filled_at")), str(rs.getString("instrument")),
                        str(rs.getString("side")), num(rs.getBigDecimal("quantity")),
                        num(rs.getBigDecimal("arrival_price")), num(rs.getBigDecimal("fill_price")),
                        num(rs.getBigDecimal("slippage_bps")), rs.getBoolean("rate_quoted") ? "rate" : "price"));
        wb.sheet("TCA", List.of("filled_at", "instrument", "side", "qty", "arrival", "fill",
                "slippage_bps", "unit"), rows);
    }

    private void hypothesesSheet(JdbcTemplate db, Xlsx wb) {
        List<List<Object>> rows = query(db,
                "select created_at, instrument, direction, conviction, horizon, book, quantity, "
                        + "backtest_supported, order_status, outcome, outcome_pnl "
                        + "from hypothesis_record order by created_at desc limit " + ROW_CAP,
                rs -> List.of(str(rs.getObject("created_at")), str(rs.getString("instrument")),
                        str(rs.getString("direction")), str(rs.getString("conviction")),
                        str(rs.getString("horizon")), str(rs.getString("book")),
                        num(rs.getBigDecimal("quantity")), rs.getBoolean("backtest_supported") ? "Y" : "N",
                        str(rs.getString("order_status")), str(rs.getString("outcome")),
                        num(rs.getBigDecimal("outcome_pnl"))));
        wb.sheet("AI hypotheses", List.of("created_at", "instrument", "direction", "conviction",
                "horizon", "book", "qty", "backtested", "order_status", "outcome", "outcome_pnl"), rows);
    }

    private void hedgeOrdersSheet(JdbcTemplate db, Xlsx wb) {
        List<List<Object>> rows = query(db,
                "select created_at, order_id, book_id, instrument_id, side, quantity, status, reason "
                        + "from orders where order_id like 'hedge:%' order by created_at desc limit " + ROW_CAP,
                rs -> List.of(str(rs.getObject("created_at")), str(rs.getString("order_id")),
                        str(rs.getString("book_id")), str(rs.getString("instrument_id")),
                        str(rs.getString("side")), num(rs.getBigDecimal("quantity")),
                        str(rs.getString("status")), str(rs.getString("reason"))));
        wb.sheet("Hedge orders", List.of("created_at", "order_id", "book", "instrument", "side",
                "qty", "status", "reason"), rows);
    }

    // ---- helpers: a query that never fails the whole export (a missing table → an empty sheet) ----

    private interface RowMapper {
        List<Object> map(java.sql.ResultSet rs) throws java.sql.SQLException;
    }

    private static List<List<Object>> query(JdbcTemplate db, String sql, RowMapper mapper) {
        List<List<Object>> rows = new ArrayList<>();
        try {
            org.springframework.jdbc.core.RowCallbackHandler handler = rs -> rows.add(mapper.map(rs));
            db.query(sql, handler);
        } catch (RuntimeException e) {
            rows.add(List.of("query failed: " + e.getMessage()));
        }
        return rows;
    }

    private static List<Object> row(String k, Object v) {
        return List.of(k, v == null ? "" : v);
    }

    private static Object num(BigDecimal v) {
        return v == null ? "" : v;
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }
}
