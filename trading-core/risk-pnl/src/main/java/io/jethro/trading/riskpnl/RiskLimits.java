package io.jethro.trading.riskpnl;

import java.math.BigDecimal;

/**
 * Per-book risk limits (risk appetite). A non-positive or null value means "no limit"
 * for that metric — the evaluator skips it. Figures are in the book's currency.
 *
 * <ul>
 *   <li>{@code maxGrossExposure} — cap on gross (sum of |notional|) exposure.</li>
 *   <li>{@code maxNetExposure} — cap on |net| directional exposure.</li>
 *   <li>{@code maxLossPnl} — max tolerable loss: breached when total PnL falls below
 *       {@code -maxLossPnl}.</li>
 * </ul>
 */
public record RiskLimits(BigDecimal maxGrossExposure, BigDecimal maxNetExposure, BigDecimal maxLossPnl) {

    private static final RiskLimits NONE = new RiskLimits(null, null, null);

    /** No limits — every check is skipped. */
    public static RiskLimits none() {
        return NONE;
    }

    static boolean isSet(BigDecimal limit) {
        return limit != null && limit.signum() > 0;
    }
}
