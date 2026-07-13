package io.jethro.app.backtest;

import java.math.BigDecimal;
import java.util.List;

/**
 * Measured outcome of a backtest — exact-decimal PnL (invariant 1), computed by the same
 * average-cost ledger the live book uses ({@code Positions}). "Edge" is realized+unrealized
 * PnL against the equity curve's worst drawdown; on a random-walk sim with regimes the
 * honest expectation is roughly break-even before costs — the point is a reproducible
 * measurement, not a promise of alpha.
 *
 * @param winRate fraction of risk-reducing (closing) fills that realised a profit NET of costs.
 * @param maxDrawdown largest peak-to-trough drop of the mark-to-market equity curve (≥ 0).
 * @param totalCosts cumulative transaction cost charged over the run (already deducted from
 *                   {@code realizedPnl}/{@code totalPnl} and the equity curve).
 * @param equityCurve sampled mark-to-market equity points (for charting), oldest first.
 */
public record BacktestResult(
        long seed, int ticks, int evaluations, int signals, int trades,
        BigDecimal realizedPnl, BigDecimal unrealizedPnl, BigDecimal totalPnl,
        BigDecimal maxDrawdown, double winRate, BigDecimal totalCosts,
        List<InstrumentResult> byInstrument, List<BigDecimal> equityCurve) {

    /** Per-instrument PnL breakdown. */
    public record InstrumentResult(String instrumentId, int trades,
                                   BigDecimal realizedPnl, BigDecimal unrealizedPnl, BigDecimal endPosition) {
    }
}
