package io.jethro.app.discovery;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Narrow write/read port the ADR-0060 promotion service uses to touch reference data — deliberately
 * small so the service is unit-testable against an in-memory fake, with no JDBC. The production
 * implementation delegates to the reference-data module and refreshes the instrument-ref cache.
 */
public interface UniverseRefdataGateway {

    /** True if the instrument already exists in the master (promotion is then a no-op — idempotent). */
    boolean exists(String instrumentId);

    /** Write a discovery-promoted instrument (base row + symbology + attributes) — a first-class name. */
    void writeMonitored(String instrumentId, String assetClass, String currency, BigDecimal multiplier,
                        Map<String, String> symbology, Map<String, String> attributes);

    /** InstrumentIds written by the runtime path (source=discovered) — the evictable set. */
    List<String> discoveredInstrumentIds();

    /** Whether the name ever traded (has a fill) — an eviction guard (never evict a name with a tape). */
    boolean hasFills(String instrumentId);

    /** Remove a discovered instrument; refuses (returns false) for a non-discovered/core name. */
    boolean evict(String instrumentId);

    /** Re-read reference data so a promotion/eviction is visible to every reader without a restart. */
    void refresh();
}
