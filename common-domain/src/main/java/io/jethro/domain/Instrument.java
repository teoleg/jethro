package io.jethro.domain;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Instrument reference data (ADR-0008). {@code symbology} maps external source name →
 * external symbol (e.g. {@code {"POLYGON": "AAPL"}}); it exists for the market-data
 * module's translation and must not be used as a key anywhere else (invariant 2).
 *
 * @param contractMultiplier currency value of one point of price movement per unit of
 *                           quantity; {@code 1} for cash equities/FX, exchange-defined
 *                           for futures/options.
 */
public record Instrument(
        InstrumentId id,
        AssetClass assetClass,
        String currency,
        BigDecimal contractMultiplier,
        Map<String, String> symbology) {

    public Instrument {
        if (contractMultiplier == null || contractMultiplier.signum() <= 0) {
            throw new IllegalArgumentException("contractMultiplier must be positive");
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("currency must be non-blank");
        }
        symbology = Map.copyOf(symbology);
    }
}
