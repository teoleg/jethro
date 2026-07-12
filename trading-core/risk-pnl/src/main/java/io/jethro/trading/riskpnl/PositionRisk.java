package io.jethro.trading.riskpnl;

import java.math.BigDecimal;

/**
 * Valued risk/PnL for one (book, instrument) position. All figures in the instrument's
 * currency at {@link io.jethro.domain.Decimals#PNL_SCALE}. {@code unrealizedPnl} and the
 * exposures are zero when there is no live mark ({@code hasMark == false}).
 *
 * <p>Sign convention: {@code quantity > 0} long. {@code netExposure = quantity · mark ·
 * multiplier} (signed); {@code grossExposure = |netExposure|}; {@code unrealizedPnl =
 * quantity · (mark − avgCost) · multiplier}.
 */
public record PositionRisk(
        String bookId,
        String instrumentId,
        String assetClass,
        String currency,
        BigDecimal quantity,
        BigDecimal avgCost,
        BigDecimal mark,
        boolean hasMark,
        long markAgeMillis,
        BigDecimal realizedPnl,
        BigDecimal unrealizedPnl,
        BigDecimal netExposure,
        BigDecimal grossExposure) {

    public BigDecimal totalPnl() {
        return realizedPnl.add(unrealizedPnl);
    }
}
