package io.jethro.trading.riskpnl;

import java.math.BigDecimal;

/**
 * The reference facts risk-pnl needs to value a position: contract multiplier (money
 * per point), asset class (for the per-market rollup), currency, and — for rates
 * instruments — the modified duration that links price to yield moves (scenario/stress),
 * plus the average daily volume the impact model reads.
 * Supplied by {@link InstrumentRefSource} — risk-pnl never reaches into the
 * reference-data module directly (ADR-0015), it depends on this port.
 *
 * @param modDuration modified duration (Δprice/price ≈ −D·Δy) for BOND-class
 *                    instruments; null where not applicable/known.
 * @param advUsd average daily volume in USD notional (market-impact + participation
 *               inputs, ADR-0025); null = unmodelled, never guessed.
 * @param notionalPerLot gross notional of ONE unit for notional-quoted instruments
 *               (a swap lot = $1M) — the exposure convention input; null for price-quoted
 *               instruments (their exposure is qty × price × multiplier).
 * @param spreadBps per-NAME full bid/ask spread in bps (ADR-0025); null = fall back to the
 *               asset-class config.
 */
public record InstrumentRef(String instrumentId, String assetClass, String currency,
                            BigDecimal multiplier, BigDecimal modDuration, BigDecimal advUsd,
                            BigDecimal notionalPerLot, BigDecimal spreadBps) {

    public InstrumentRef(String instrumentId, String assetClass, String currency,
                         BigDecimal multiplier, BigDecimal modDuration, BigDecimal advUsd,
                         BigDecimal notionalPerLot) {
        this(instrumentId, assetClass, currency, multiplier, modDuration, advUsd, notionalPerLot, null);
    }

    public InstrumentRef(String instrumentId, String assetClass, String currency,
                         BigDecimal multiplier, BigDecimal modDuration, BigDecimal advUsd) {
        this(instrumentId, assetClass, currency, multiplier, modDuration, advUsd, null, null);
    }

    public InstrumentRef(String instrumentId, String assetClass, String currency,
                         BigDecimal multiplier, BigDecimal modDuration) {
        this(instrumentId, assetClass, currency, multiplier, modDuration, null, null, null);
    }

    /** Instruments without a rates sensitivity (equities, FX, ...): no duration. */
    public InstrumentRef(String instrumentId, String assetClass, String currency, BigDecimal multiplier) {
        this(instrumentId, assetClass, currency, multiplier, null, null, null, null);
    }

    /** Fallback when reference data is missing: multiplier 1, so PnL is at least well-defined. */
    public static InstrumentRef unknown(String instrumentId) {
        return new InstrumentRef(instrumentId, "UNKNOWN", "USD", BigDecimal.ONE, null, null, null, null);
    }
}
