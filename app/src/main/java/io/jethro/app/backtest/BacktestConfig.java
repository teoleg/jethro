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
 * @param volatileScale  new-entry sizing multiplier in the VOLATILE regime (risk-off), so the
 *                       backtest mirrors the live strategy's regime-awareness. 1 = no scaling.
 * @param costBps        round-trip-agnostic transaction cost charged per fill, in bps of
 *                       traded notional (fees + slippage proxy). 0 = frictionless.
 */
public record BacktestConfig(
        long seed, int ticks, boolean regimes, int evalEveryTicks,
        int lookback, double thresholdSigmas, BigDecimal minSignalBps,
        BigDecimal targetNotional, BigDecimal maxOrderNotional, BigDecimal maxPositionNotional,
        boolean allowShort, BigDecimal volatileScale, BigDecimal costBps, List<Instrument> instruments,
        /** Signal algo under test: "momentum" or "mean-reversion" (same detector, opposite
         *  reading — the harness adjudicates between them, ADR-0027). */
        String algo) {

    /** Momentum config (the original shape — existing call sites/tests unchanged). */
    public BacktestConfig(long seed, int ticks, boolean regimes, int evalEveryTicks,
                          int lookback, double thresholdSigmas, BigDecimal minSignalBps,
                          BigDecimal targetNotional, BigDecimal maxOrderNotional, BigDecimal maxPositionNotional,
                          boolean allowShort, BigDecimal volatileScale, BigDecimal costBps,
                          List<Instrument> instruments) {
        this(seed, ticks, regimes, evalEveryTicks, lookback, thresholdSigmas, minSignalBps,
                targetNotional, maxOrderNotional, maxPositionNotional, allowShort, volatileScale,
                costBps, instruments, "momentum");
    }

    /** One tradable instrument in the backtest universe. */
    public record Instrument(String instrumentId, BigDecimal startPrice, double annualVol,
                             BigDecimal multiplier) {
    }
}
