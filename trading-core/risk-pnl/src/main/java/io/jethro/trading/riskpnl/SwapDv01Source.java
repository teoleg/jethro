package io.jethro.trading.riskpnl;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Live per-lot swap DV01 (USD per 1bp par move on a $1M pay-fixed lot, positive — the
 * annuity) for the ledger's <b>dynamic</b> swap multiplier: the V9 quoting convention
 * (P&amp;L = qty × Δpar-in-points × multiplier) becomes economically live when multiplier =
 * DV01 × 100 is re-read from the Strata pricer instead of held at its inception constant.
 * Empty when the curve isn't priced yet — callers fall back to the static reference-data
 * multiplier (the disclosed V9 approximation), never to zero.
 */
public interface SwapDv01Source {

    Optional<BigDecimal> dv01PerLot(String instrumentId);

    /** No live pricing — static reference-data multipliers only. */
    SwapDv01Source NONE = instrumentId -> Optional.empty();
}
