package io.jethro.app.risk;

import io.jethro.trading.riskpnl.ConsolidatedRisk;
import io.jethro.trading.riskpnl.CurveService;
import io.jethro.trading.riskpnl.PositionRisk;
import io.jethro.trading.riskpnl.RiskProjection;
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

    public record TotalsDto(String realizedPnl, String unrealizedPnl, String totalPnl,
                            String grossExposure, String netExposure) {
    }

    public record GroupDto(String key, String currency, String realizedPnl, String unrealizedPnl,
                           String totalPnl, String grossExposure, String netExposure, int positionCount) {
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

    public RiskController(RiskProjection projection, CurveService curveService) {
        this.projection = projection;
        this.curveService = curveService;
    }

    /** Live SOFR zero curve (rates/DFs are analytics estimates, not ledger money). */
    @GetMapping("/api/curve")
    public java.util.List<CurveService.CurvePoint> curve() {
        return curveService.snapshot();
    }

    @GetMapping("/api/risk")
    public RiskDto risk() {
        ConsolidatedRisk r = projection.snapshot(System.currentTimeMillis());
        return new RiskDto(
                r.asOfMillis(),
                new TotalsDto(s(r.total().realizedPnl()), s(r.total().unrealizedPnl()), s(r.total().totalPnl()),
                        s(r.total().grossExposure()), s(r.total().netExposure())),
                r.byAssetClass().stream().map(RiskController::group).toList(),
                r.byBook().stream().map(RiskController::group).toList(),
                r.positions().stream().map(RiskController::position).toList());
    }

    private static GroupDto group(ConsolidatedRisk.Group g) {
        return new GroupDto(g.key(), g.currency(), s(g.realizedPnl()), s(g.unrealizedPnl()),
                s(g.totalPnl()), s(g.grossExposure()), s(g.netExposure()), g.positionCount());
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
