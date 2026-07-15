package io.jethro.app.risk;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Measured per-instrument daily volatility (fraction/day, e.g. 0.018 = 1.8%) for
 * vol-targeted position sizing. Empty when the instrument hasn't accrued enough daily
 * history — callers fall back to their fixed-notional sizing, disclosed, never a guess.
 */
public interface InstrumentVolSource {

    Optional<BigDecimal> dailyVol(String instrumentId);

    /** No measured vol available (persistence off / warm-up) — fixed-notional sizing only. */
    InstrumentVolSource NONE = instrumentId -> Optional.empty();
}
