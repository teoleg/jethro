package io.jethro.trading.riskpnl;

import java.math.BigDecimal;
import java.util.List;

/**
 * Firm-wide risk/PnL snapshot: headline totals, the per-asset-class and per-book
 * rollups that drive the multi-asset views, and the underlying positions. Built by
 * {@link RiskProjection#snapshot(long)}; figures are exact decimals (invariant 1).
 */
public record ConsolidatedRisk(
        long asOfMillis,
        Totals total,
        List<Group> byAssetClass,
        List<Group> byBook,
        List<PositionRisk> positions) {

    /** Headline figures across every book. */
    public record Totals(
            BigDecimal realizedPnl,
            BigDecimal unrealizedPnl,
            BigDecimal totalPnl,
            BigDecimal grossExposure,
            BigDecimal netExposure) {
    }

    /** A rollup bucket — one asset class or one book. */
    public record Group(
            String key,
            String currency,
            BigDecimal realizedPnl,
            BigDecimal unrealizedPnl,
            BigDecimal totalPnl,
            BigDecimal grossExposure,
            BigDecimal netExposure,
            int positionCount) {
    }
}
