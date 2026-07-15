package io.jethro.trading.riskpnl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * EWMA covariance over daily return vectors (RiskMetrics λ=0.94) and the parametric
 * (variance–covariance) VaR built on it — the assumption-carrying counterpart to
 * {@link VarMath}'s assumption-light historical simulation. Both are shown side by side;
 * neither replaces the other.
 *
 * <pre>
 *   Σ_t = λ·Σ_{t−1} + (1−λ)·r_t·r_tᵀ            (seeded with the first outer product)
 *   σ_p = √(wᵀ Σ w)                              w = USD exposures
 *   VaR_α = z_α · σ_p                            z₉₅ = 1.645, z₉₉ = 2.326  (NORMAL quantiles
 *                                                — the stated assumption; real tails are
 *                                                fatter, which is why historical VaR stays)
 *   ES₉₅  = σ_p · φ(z₉₅)/0.05 = σ_p × 2.0627
 * </pre>
 *
 * Strict coverage like the historical path: only instruments present in EVERY day vector
 * enter Σ; exposures without covered history are reported as skipped, never guessed.
 * Statistics in double; money at the boundary (invariant 1).
 */
public final class CovMath {

    public static final double LAMBDA = 0.94;
    static final double Z_95 = 1.645;
    static final double Z_99 = 2.326;
    static final double ES_95_FACTOR = 2.0627; // φ(1.645)/0.05 under the normal assumption

    /** A covariance estimate over an instrument set (aligned, strict coverage). */
    public record Covariance(List<String> instruments, double[][] sigma, int observations) {

        /** Portfolio daily σ in USD for the given exposures (missing instruments excluded). */
        public double portfolioSigmaUsd(Map<String, BigDecimal> exposuresUsd) {
            double[] w = weights(exposuresUsd);
            double var = 0;
            for (int i = 0; i < w.length; i++) {
                for (int j = 0; j < w.length; j++) {
                    var += w[i] * sigma[i][j] * w[j];
                }
            }
            return Math.sqrt(Math.max(0, var));
        }

        /**
         * Correlation of ONE instrument's returns with the current portfolio's returns:
         * ρ_ip = (Σw)_i / (σ_i · σ_p). Empty when the instrument isn't covered, the portfolio
         * is empty/degenerate, or σ_i is 0 — callers fall back to standalone sizing, disclosed.
         */
        public Optional<Double> correlationToPortfolio(String instrumentId, Map<String, BigDecimal> exposuresUsd) {
            int idx = instruments.indexOf(instrumentId);
            if (idx < 0) {
                return Optional.empty();
            }
            double[] w = weights(exposuresUsd);
            double sigmaP = portfolioSigmaUsd(exposuresUsd);
            double sigmaI = Math.sqrt(sigma[idx][idx]);
            if (sigmaP <= 0 || sigmaI <= 0) {
                return Optional.empty();
            }
            double cross = 0;
            for (int j = 0; j < w.length; j++) {
                cross += sigma[idx][j] * w[j];
            }
            return Optional.of(Math.max(-1.0, Math.min(1.0, cross / (sigmaI * sigmaP))));
        }

        private double[] weights(Map<String, BigDecimal> exposuresUsd) {
            double[] w = new double[instruments.size()];
            for (int i = 0; i < w.length; i++) {
                BigDecimal e = exposuresUsd.get(instruments.get(i));
                w[i] = e != null ? e.doubleValue() : 0;
            }
            return w;
        }
    }

    /** Parametric VaR result; money at the boundary, note carries the assumption. */
    public record ParametricVar(BigDecimal var95, BigDecimal es95, BigDecimal var99,
                                int observations, BigDecimal coveredExposure,
                                BigDecimal skippedExposure, String note) {
    }

    private CovMath() {
    }

    /**
     * EWMA covariance from consecutive-day return vectors (oldest first, {@link VarMath.DayVector}
     * shape). Strict coverage: the instrument set is the intersection across ALL days. Empty
     * below {@code minObservations} days.
     */
    public static Optional<Covariance> ewmaCovariance(List<VarMath.DayVector> days, int minObservations) {
        if (days.size() < Math.max(2, minObservations)) {
            return Optional.empty();
        }
        List<String> instruments = new ArrayList<>(days.get(0).returns().keySet());
        for (VarMath.DayVector day : days) {
            instruments.retainAll(day.returns().keySet());
        }
        if (instruments.isEmpty()) {
            return Optional.empty();
        }
        instruments.sort(String::compareTo); // deterministic axis regardless of map ordering
        int n = instruments.size();
        double[][] sigma = new double[n][n];
        boolean first = true;
        for (VarMath.DayVector day : days) {
            double[] r = new double[n];
            for (int i = 0; i < n; i++) {
                r[i] = day.returns().get(instruments.get(i));
            }
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    double outer = r[i] * r[j];
                    sigma[i][j] = first ? outer : LAMBDA * sigma[i][j] + (1 - LAMBDA) * outer;
                }
            }
            first = false;
        }
        return Optional.of(new Covariance(List.copyOf(instruments), sigma, days.size()));
    }

    /** Parametric VaR over the covered exposures; uncovered exposure disclosed as skipped. */
    public static ParametricVar parametricVar(Map<String, BigDecimal> exposuresUsd, Covariance cov) {
        BigDecimal covered = BigDecimal.ZERO;
        BigDecimal skipped = BigDecimal.ZERO;
        Map<String, BigDecimal> coveredExposures = new LinkedHashMap<>();
        for (var e : exposuresUsd.entrySet()) {
            if (cov.instruments().contains(e.getKey())) {
                covered = covered.add(e.getValue().abs());
                coveredExposures.put(e.getKey(), e.getValue());
            } else {
                skipped = skipped.add(e.getValue().abs());
            }
        }
        double sigmaP = cov.portfolioSigmaUsd(coveredExposures);
        return new ParametricVar(
                money(Z_95 * sigmaP), money(ES_95_FACTOR * sigmaP), money(Z_99 * sigmaP),
                cov.observations(), money(covered.doubleValue()), money(skipped.doubleValue()),
                "normal EWMA(λ=0.94) covariance — thin-tailed by assumption; compare with historical VaR");
    }

    private static BigDecimal money(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }
}
