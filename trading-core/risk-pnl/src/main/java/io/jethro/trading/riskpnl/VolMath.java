package io.jethro.trading.riskpnl;

import java.util.OptionalDouble;

/**
 * Realized-volatility estimation over recorded daily returns — the measured input for
 * vol-targeted position sizing (and, later, covariance work). RiskMetrics EWMA:
 *
 * <pre>  σ²_t = λ·σ²_{t−1} + (1−λ)·r²_t,   λ = 0.94</pre>
 *
 * seeded with the first squared return, oldest first — recent turbulence dominates, calm
 * history decays. Pure statistical parameter (double is fine here; invariant 1 applies at
 * the money boundary where a caller multiplies vol into notional). Below
 * {@code minObservations} returns empty — an unmeasured vol is disclosed, never guessed.
 */
public final class VolMath {

    public static final double LAMBDA = 0.94;

    private VolMath() {
    }

    /**
     * EWMA daily vol (fraction/day, e.g. 0.018 = 1.8%) over consecutive daily returns,
     * oldest first. Empty below {@code minObservations} or if the estimate degenerates.
     */
    public static OptionalDouble ewmaDailyVol(double[] returns, int minObservations) {
        if (returns.length < Math.max(1, minObservations)) {
            return OptionalDouble.empty();
        }
        double variance = returns[0] * returns[0];
        for (int i = 1; i < returns.length; i++) {
            variance = LAMBDA * variance + (1 - LAMBDA) * returns[i] * returns[i];
        }
        double vol = Math.sqrt(variance);
        return vol > 0 && Double.isFinite(vol) ? OptionalDouble.of(vol) : OptionalDouble.empty();
    }
}
