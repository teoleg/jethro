package io.jethro.app.backtest;

import java.math.BigDecimal;
import java.util.List;

/**
 * Inputs to a deterministic backtest (build step 8). The sim tape is the historical
 * price source (seedable, ADR-0009) — same seed + config → identical result, so a
 * backtest is reproducible (the whole point). Only price-quoted instruments
 * (equity/future/FX) run here; curve-linked rates/swaps price off the factor curve and
 * get their own backtest later.
 *
 * @param evalEveryTicks strategy cadence in ticks (live: 5s / 100ms tick = every 50).
 */
public record BacktestConfig(
        long seed, int ticks, boolean regimes, int evalEveryTicks,
        int lookback, double thresholdSigmas, BigDecimal minSignalBps,
        BigDecimal targetNotional, BigDecimal maxOrderNotional, BigDecimal maxPositionNotional,
        boolean allowShort, List<Instrument> instruments) {

    /** One tradable instrument in the backtest universe. */
    public record Instrument(String instrumentId, BigDecimal startPrice, double annualVol,
                             BigDecimal multiplier) {
    }
}
