package io.jethro.trading.riskpnl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Deterministic pre-trade limit check (ADR-0018): would this order push the book's gross
 * or net exposure over its cap? Guardrails are code, never a model (invariant 7); exact
 * BigDecimal comparisons (invariant 1). Returns a rejection reason, or empty to approve.
 *
 * <p>Exposure at exactly the cap is allowed; only strictly exceeding it rejects. Books
 * with no cap for a metric never reject on it.
 */
public final class PreTradeGuardrail {

    private final RiskProjection projection;
    private final RiskLimitSource limits;

    public PreTradeGuardrail(RiskProjection projection, RiskLimitSource limits) {
        this.projection = projection;
        this.limits = limits;
    }

    /**
     * @param signedQuantity order quantity signed by side (BUY positive, SELL negative).
     * @return a rejection reason if the order would breach a cap, else empty (approved).
     */
    public Optional<String> rejectionReason(String bookId, String instrumentId, BigDecimal signedQuantity) {
        RiskLimits lim = limits.limitsFor(bookId);
        RiskProjection.Exposure projected = projection.projectedExposure(bookId, instrumentId, signedQuantity);

        if (RiskLimits.isSet(lim.maxGrossExposure())
                && projected.gross().compareTo(lim.maxGrossExposure()) > 0) {
            return Optional.of("gross exposure " + plain(projected.gross()) + " would exceed "
                    + bookId + " limit " + plain(lim.maxGrossExposure()));
        }
        if (RiskLimits.isSet(lim.maxNetExposure())
                && projected.net().abs().compareTo(lim.maxNetExposure()) > 0) {
            return Optional.of("net exposure " + plain(projected.net().abs()) + " would exceed "
                    + bookId + " limit " + plain(lim.maxNetExposure()));
        }
        return Optional.empty();
    }

    private static String plain(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
