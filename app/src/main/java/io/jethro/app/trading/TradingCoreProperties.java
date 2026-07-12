package io.jethro.app.trading;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Assembly-level wiring config for trading-core (ADR-0015: wiring lives in app). */
@ConfigurationProperties(prefix = "jethro.trading")
public record TradingCoreProperties(
        boolean enabled,
        long simSeed,
        List<String> simInstruments,
        BigDecimal simStartPrice,
        /** Optional per-instrument start prices; instruments not listed use simStartPrice. */
        Map<String, BigDecimal> simStartPrices,
        long simTickIntervalMillis,
        String lmdbPath,
        long lmdbMaxSizeMb,
        int bufferCapacity) {

    /** Start price for one instrument: its override if present, else the default. */
    public BigDecimal startPriceFor(String instrumentId) {
        BigDecimal override = simStartPrices == null ? null : simStartPrices.get(instrumentId);
        return override != null ? override : simStartPrice;
    }
}
