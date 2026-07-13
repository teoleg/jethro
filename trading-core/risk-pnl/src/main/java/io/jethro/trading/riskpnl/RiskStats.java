package io.jethro.trading.riskpnl;

import io.jethro.domain.Decimals;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Statistical risk measures (ADR-0020), library-backed by Apache Commons Math — not
 * hand-rolled. These are <em>estimates</em>, computed in {@code double}: volatility,
 * parametric VaR, concentration. The money boundary is explicit — VaR is returned as an
 * exact {@link BigDecimal} in currency; volatility/concentration are dimensionless
 * doubles. Money in/out of here stays {@link BigDecimal} (invariant 1).
 */
public final class RiskStats {

    private RiskStats() {
    }

    /**
     * Sample volatility = standard deviation of log returns of the price series
     * (Commons Math {@link DescriptiveStatistics}, n−1). Returns 0 for fewer than two
     * usable returns. Per-observation (not annualised) — the caller's window defines the
     * horizon.
     */
    public static double logReturnVolatility(double[] prices) {
        DescriptiveStatistics returns = new DescriptiveStatistics();
        for (int i = 1; i < prices.length; i++) {
            if (prices[i - 1] > 0 && prices[i] > 0) {
                returns.addValue(Math.log(prices[i] / prices[i - 1]));
            }
        }
        return returns.getN() < 2 ? 0.0 : returns.getStandardDeviation();
    }

    /**
     * Parametric (variance-covariance) VaR at the money boundary: {@code z · σ · |exposure|}.
     * z is the confidence z-score (1.645 = 95%, 2.326 = 99%). Result in the exposure's
     * currency at {@link Decimals#PNL_SCALE}. Assumes normally distributed returns —
     * understates tail risk (ADR-0020).
     */
    public static BigDecimal parametricVar(double sigma, BigDecimal exposure, double zScore) {
        double var = zScore * sigma * exposure.abs().doubleValue();
        return BigDecimal.valueOf(var).setScale(Decimals.PNL_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Herfindahl-Hirschman concentration over gross exposures: {@code Σ(shareᵢ)²}, in
     * [1/n, 1]. 1 = a single position; lower = more diversified. Dimensionless.
     */
    public static double herfindahl(List<BigDecimal> grossExposures) {
        double total = 0.0;
        for (BigDecimal e : grossExposures) {
            total += e.abs().doubleValue();
        }
        if (total <= 0.0) {
            return 0.0;
        }
        double hhi = 0.0;
        for (BigDecimal e : grossExposures) {
            double share = e.abs().doubleValue() / total;
            hhi += share * share;
        }
        return hhi;
    }
}
