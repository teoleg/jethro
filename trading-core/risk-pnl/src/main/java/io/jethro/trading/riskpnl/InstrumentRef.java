package io.jethro.trading.riskpnl;

import java.math.BigDecimal;

/**
 * The reference facts risk-pnl needs to value a position: contract multiplier (money
 * per point), asset class (for the per-market rollup), currency, and — for rates
 * instruments — the modified duration that links price to yield moves (scenario/stress).
 * Supplied by {@link InstrumentRefSource} — risk-pnl never reaches into the
 * reference-data module directly (ADR-0015), it depends on this port.
 *
 * @param modDuration modified duration (Δprice/price ≈ −D·Δy) for BOND-class
 *                    instruments; null where not applicable/known.
 */
public record InstrumentRef(String instrumentId, String assetClass, String currency,
                            BigDecimal multiplier, BigDecimal modDuration) {

    /** Instruments without a rates sensitivity (equities, FX, ...): no duration. */
    public InstrumentRef(String instrumentId, String assetClass, String currency, BigDecimal multiplier) {
        this(instrumentId, assetClass, currency, multiplier, null);
    }

    /** Fallback when reference data is missing: multiplier 1, so PnL is at least well-defined. */
    public static InstrumentRef unknown(String instrumentId) {
        return new InstrumentRef(instrumentId, "UNKNOWN", "USD", BigDecimal.ONE, null);
    }
}
