package io.jethro.trading.riskpnl;

import java.util.Optional;

/** Port to instrument reference data (multiplier/assetClass/currency). Wired to the
 *  reference-data module in the app assembly; kept an interface so risk-pnl stays a
 *  pure projection, testable without a database. */
public interface InstrumentRefSource {

    Optional<InstrumentRef> find(String instrumentId);
}
