package io.jethro.app.trading;

import io.jethro.messaging.FeedMode;
import io.jethro.messaging.Provenance;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

/**
 * Stamps this run's data provenance (ADR-0029) before any event is published: the {@link FeedMode}
 * derived from the configured market-data provider, and a fresh {@code sessionEpoch}. Done in a
 * bean constructor so it runs during context refresh — ahead of the lifecycle/data-flow phase when
 * the first event is produced. A runtime feed switch (ADR-0029 stage 3) will re-{@code configure}
 * with a new epoch.
 */
@Configuration
public class ProvenanceConfig {

    public ProvenanceConfig(TradingCoreProperties properties) {
        Provenance.configure(
                feedModeFor(properties.providerOrDefault()),
                Long.toHexString(System.currentTimeMillis()) + "-" + UUID.randomUUID().toString().substring(0, 8));
    }

    /** sim → SIM; a real provider → LIVE; the replay adapter → REPLAY. */
    static FeedMode feedModeFor(String provider) {
        return switch (provider == null ? "sim" : provider.trim().toLowerCase()) {
            case "yahoo", "finnhub" -> FeedMode.LIVE;
            case "replay" -> FeedMode.REPLAY;
            default -> FeedMode.SIM;
        };
    }
}
