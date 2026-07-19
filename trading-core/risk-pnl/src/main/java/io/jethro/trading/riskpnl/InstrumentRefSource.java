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
}
