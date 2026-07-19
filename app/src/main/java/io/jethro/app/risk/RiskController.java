package io.jethro.app.risk;

import io.jethro.trading.riskpnl.ConsolidatedRisk;
import io.jethro.trading.riskpnl.CurveService;
import io.jethro.trading.riskpnl.PositionRisk;
import io.jethro.trading.riskpnl.RiskProjection;
import io.jethro.trading.riskpnl.SwapPricingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * Consolidated risk/PnL REST surface for the UI: headline totals, the per-asset-class
 * and per-book rollups behind the multi-asset views, and the positions. All decimals are
 * strings at the boundary (invariant 1 — never a JS float).
 */
@RestController
public final class RiskController {

    // P&L is split clean vs comprehensive (ADR-0037): totalPnl is the clean trading figure
    // (locked realized + unrealized — holds still when flat); fxTranslationPnl is the live
    // revaluation of foreign realized cash; comprehensivePnl is actual book-value change.
    public record TotalsDto(String realizedPnl, String unrealizedPnl, String totalPnl,
                            String fxTranslationPnl, String comprehensivePnl,
                            String grossExposure, String netExposure) {
    }

    public record GroupDto(String key, String currency, String realizedPnl, String unrealizedPnl,
                           String totalPnl, String fxTranslationPnl, String comprehensivePnl,
                           String grossExposure, String netExposure, int positionCount) {
    }

    public record PositionDto(String bookId, String instrumentId, String assetClass, String currency,
                              String quantity, String avgCost, String mark, boolean hasMark, long markAgeMillis,
                              String realizedPnl, String unrealizedPnl, String totalPnl,
                              String netExposure, String grossExposure) {
    }

    public record RiskDto(long asOfMillis, TotalsDto total, List<GroupDto> byAssetClass,
                          List<GroupDto> byBook, List<PositionDto> positions) {
    }

    private final RiskProjection projection;
    private final CurveService curveService;
    private final io.jethro.trading.riskpnl.TreasuryCurveView treasuryCurve;
    private final SwapPricingService swapPricing;
    private final io.jethro.trading.riskpnl.ScenarioEngine scenarios;
    private final java.util.function.Supplier<java.time.LocalDate> sessionDay; // session calendar (finding 3)

    public RiskController(RiskProjection projection, CurveService curveService,
                          io.jethro.trading.riskpnl.TreasuryCurveView treasuryCurve,
                          SwapPricingService swapPricing,
                          io.jethro.trading.riskpnl.ScenarioEngine scenarios,
                          java.util.function.Supplier<java.time.LocalDate> sessionDay) {
        this.projection = projection;
        this.curveService = curveService;
        this.treasuryCurve = treasuryCurve;
        this.swapPricing = swapPricing;
        this.scenarios = scenarios;
        this.sessionDay = sessionDay != null ? sessionDay : java.time.LocalDate::now;
    }

    public record BookImpactDto(String bookId, String pnlUsd) {
    }

    public record ScenarioDto(String id, String name, String firmPnlUsd,
                              List<BookImpactDto> byBook, int positionsCovered, int positionsSkipped) {
    }

    /** Scenario/stress P&L: current positions revalued under the standard shocks
     *  (first-order, deterministic — quant-engine step 2). Decimals as strings. */
    @GetMapping("/api/scenarios")
    public List<ScenarioDto> scenarios() {
        ConsolidatedRisk r = projection.snapshot(System.currentTimeMillis());
        return scenarios.run(r.positions(), projection.fx()).stream()
                .map(s -> new ScenarioDto(s.id(), s.name(), s.firmPnlUsd().toPlainString(),
                        s.byBook().stream()
                                .map(b -> new BookImpactDto(b.bookId(), b.pnlUsd().toPlainString()))
                                .toList(),
                        s.positionsCovered(), s.positionsSkipped()))
                .toList();
    }

    /** PV/DV01 as strings (money boundary, invariant 1); rates are analytics doubles. */
    public record SwapDto(String instrumentId, String tenor, double notional, double fixedRate,
                          double parRate, String presentValue, String dv01) {
    }

    /** Reference-swap valuations (Strata pricer over the live curve). */
    @GetMapping("/api/swaps")
    public List<SwapDto> swaps() {
        return swapPricing.valueAll(sessionDay.get()).stream()
                .map(v -> new SwapDto(v.instrumentId(), v.tenor(), v.notional(), v.fixedRate(),
                        v.parRate(), v.presentValue().toPlainString(), v.dv01().toPlainString()))
                .toList();
    }

    /** Live SOFR zero curve (rates/DFs are analytics estimates, not ledger money). */
    @GetMapping("/api/curve")
    public java.util.List<CurveService.CurvePoint> curve() {
        return curveService.snapshot();
    }

    /** The DISTINCT US Treasury par curve — the swap spread vs SOFR is real information. */
    @GetMapping("/api/curve/tsy")
    public java.util.List<io.jethro.trading.riskpnl.TreasuryCurveView.TsyPoint> tsyCurve() {
        return treasuryCurve.snapshot();
    }

    @GetMapping("/api/risk")
    public RiskDto risk() {
        ConsolidatedRisk r = projection.snapshot(System.currentTimeMillis());
        return new RiskDto(
                r.asOfMillis(),
                new TotalsDto(s(r.total().realizedPnl()), s(r.total().unrealizedPnl()), s(r.total().totalPnl()),
                        s(r.total().fxTranslationPnl()), s(r.total().comprehensivePnl()),
                        s(r.total().grossExposure()), s(r.total().netExposure())),
                r.byAssetClass().stream().map(RiskController::group).toList(),
                r.byBook().stream().map(RiskController::group).toList(),
                r.positions().stream().map(RiskController::position).toList());
    }

    private static GroupDto group(ConsolidatedRisk.Group g) {
        return new GroupDto(g.key(), g.currency(), s(g.realizedPnl()), s(g.unrealizedPnl()),
                s(g.totalPnl()), s(g.fxTranslationPnl()), s(g.comprehensivePnl()),
                s(g.grossExposure()), s(g.netExposure()), g.positionCount());
    }

    private static PositionDto position(PositionRisk p) {
        return new PositionDto(p.bookId(), p.instrumentId(), p.assetClass(), p.currency(),
                s(p.quantity()), s(p.avgCost()), s(p.mark()), p.hasMark(), p.markAgeMillis(),
                s(p.realizedPnl()), s(p.unrealizedPnl()), s(p.totalPnl()), s(p.netExposure()), s(p.grossExposure()));
    }

    private static String s(BigDecimal value) {
        return value.toPlainString();
    }
}
