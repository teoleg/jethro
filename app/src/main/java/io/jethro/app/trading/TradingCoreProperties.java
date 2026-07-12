package io.jethro.app.trading;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;

/** Assembly-level wiring config for trading-core (ADR-0015: wiring lives in app). */
@ConfigurationProperties(prefix = "jethro.trading")
public record TradingCoreProperties(
        boolean enabled,
        long simSeed,
        List<String> simInstruments,
        BigDecimal simStartPrice,
        long simTickIntervalMillis,
        String lmdbPath,
        long lmdbMaxSizeMb,
        int bufferCapacity) {
}
