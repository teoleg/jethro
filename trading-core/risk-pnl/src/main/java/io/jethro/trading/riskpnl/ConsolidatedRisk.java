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

    /**
     * Headline figures across every book, in USD. P&amp;L is split clean vs comprehensive
     * (ADR-0037): {@code realizedPnl} is locked at each closing fill's FX rate, so {@code
     * totalPnl} (= realized + unrealized) is the strategy's <b>clean trading P&amp;L</b> and
     * holds still once flat. {@code fxTranslationPnl} is the revaluation of foreign realized
     * cash at live spot, and {@code comprehensivePnl} (= totalPnl + fxTranslationPnl) is the
     * <b>actual</b> book-value change — what risk controls (drawdown breaker, loss caps) read.
     */
    public record Totals(
            BigDecimal realizedPnl,
            BigDecimal unrealizedPnl,
            BigDecimal totalPnl,
            BigDecimal fxTranslationPnl,
            BigDecimal comprehensivePnl,
            BigDecimal grossExposure,
            BigDecimal netExposure) {
    }

    /** A rollup bucket — one asset class or one book. Same clean/comprehensive split as
     *  {@link Totals} (ADR-0037): {@code totalPnl} is clean (flat ⇒ static), {@code
     *  comprehensivePnl} is actual money incl. FX translation. */
    public record Group(
            String key,
            String currency,
            BigDecimal realizedPnl,
            BigDecimal unrealizedPnl,
            BigDecimal totalPnl,
            BigDecimal fxTranslationPnl,
            BigDecimal comprehensivePnl,
            BigDecimal grossExposure,
            BigDecimal netExposure,
            int positionCount) {
    }
}
