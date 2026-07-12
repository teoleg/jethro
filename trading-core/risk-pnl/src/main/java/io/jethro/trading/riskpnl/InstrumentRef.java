package io.jethro.trading.riskpnl;

import java.math.BigDecimal;

/**
 * The reference facts risk-pnl needs to value a position: contract multiplier (money
 * per point), asset class (for the per-market rollup), and currency. Supplied by
 * {@link InstrumentRefSource} — risk-pnl never reaches into the reference-data module
 * directly (ADR-0015), it depends on this port.
 */
public record InstrumentRef(String instrumentId, String assetClass, String currency, BigDecimal multiplier) {

    /** Fallback when reference data is missing: multiplier 1, so PnL is at least well-defined. */
    public static InstrumentRef unknown(String instrumentId) {
        return new InstrumentRef(instrumentId, "UNKNOWN", "USD", BigDecimal.ONE);
    }
}
