package io.jethro.trading.riskpnl;

import java.math.BigDecimal;

/**
 * Valued risk/PnL for one (book, instrument) position. All figures in the instrument's
 * currency at {@link io.jethro.domain.Decimals#PNL_SCALE}, EXCEPT {@code realizedPnlBase},
 * which is USD. {@code unrealizedPnl} and the exposures are zero when there is no live mark
 * ({@code hasMark == false}).
 *
 * <p>Sign convention: {@code quantity > 0} long. {@code netExposure = quantity · mark ·
 * multiplier} (signed); {@code grossExposure = |netExposure|}; {@code unrealizedPnl =
 * quantity · (mark − avgCost) · multiplier}.
 *
 * <p><b>Clean vs comprehensive realized P&amp;L (ADR-0037).</b> {@code realizedPnl} is the
 * local-currency fact — money already banked in the instrument's currency. {@code
 * realizedPnlBase} is that same P&amp;L translated to USD <i>at the FX rate in force when
 * each closing fill booked</i> and then frozen — it never re-wanders with spot. The gap
 * between {@code realizedPnlBase} and today's live translation of {@code realizedPnl} is
 * FX-translation P&amp;L, surfaced at the rollup, not smeared into the strategy's number.
 * For a USD instrument the two are equal.
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
        BigDecimal realizedPnlBase,
        BigDecimal unrealizedPnl,
        BigDecimal netExposure,
        BigDecimal grossExposure) {

    public BigDecimal totalPnl() {
        return realizedPnl.add(unrealizedPnl);
    }
}
