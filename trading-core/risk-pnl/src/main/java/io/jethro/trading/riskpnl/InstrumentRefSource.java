package io.jethro.trading.riskpnl;

import java.util.Optional;
import java.util.Set;

/** Port to instrument reference data (multiplier/assetClass/currency). Wired to the
 *  reference-data module in the app assembly; kept an interface so risk-pnl stays a
 *  pure projection, testable without a database. */
public interface InstrumentRefSource {

    Optional<InstrumentRef> find(String instrumentId);

    /** All known instrument ids from the reference-data master — the real, feed-independent universe.
     *  Default empty for lightweight test doubles that only implement {@link #find}. */
    default Set<String> instrumentIds() {
        return Set.of();
    }

    /**
     * Whether the instrument is monitor-only (ADR-0060): a discovery-promoted name that flows into
     * marks/indicators/signals but MUST NOT trade until its ADV is measured and it clears the OOS gate.
     * Default false — normal (core) instruments are tradable. The order path (fusion, the sole origin)
     * vetoes any monitor-only name, so growth never bleeds risk.
     */
    default boolean monitorOnly(String instrumentId) {
        return false;
    }
}
