package io.jethro.trading.riskpnl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Historical-simulation VaR (ADR-0027), pure and deterministic: TODAY's exposures revalued
 * under each of the last K days' observed return vectors — assumption-light (no normality,
 * no covariance estimate), and it prices hedges/diversification the notional caps cannot see.
 *
 * <pre>
 *   pnl_d = Σ_i exposureUSD_i × r_{d,i}          d = 1..K historical days
 *   VaR₉₅ = −(5th percentile of pnl)             (a POSITIVE dollar loss figure)
 *   ES₉₅  = −mean(pnl beyond that percentile)    (average tail loss)
 * </pre>
 *
 * Percentile CONVENTION (stated): index ⌊α·K⌋ of the ascending-sorted pnl vector. Coverage is
 * strict: an instrument enters only if it has a return on EVERY usable day; otherwise its
 * |exposure| is reported as skipped — never silently treated as riskless (data-path rule).
 * Analytics run in double; money crosses back to {@link BigDecimal} at the boundary only
 * (invariant 1). Estimates, not ledger money.
 */
public final class VarMath {

    private VarMath() {
    }

    /** One historical day's instrument returns (fraction, e.g. −0.02 = −2%). */
    public record DayVector(LocalDate day, Map<String, Double> returns) {
    }

    /**
     * @param observations   usable historical days; 0 with a reason when below {@code minObs}.
     * @param skippedExposure Σ|exposure| of positions excluded for missing history.
     */
    public record VarResult(BigDecimal var95, BigDecimal es95, BigDecimal var99,
                            int observations, BigDecimal coveredExposure, BigDecimal skippedExposure,
                            String note) {
    }

    public static VarResult historicalVar(Map<String, BigDecimal> exposuresUsd,
                                          List<DayVector> days, int minObs) {
        if (exposuresUsd.isEmpty()) {
            return empty(days.size(), "no positions");
        }
        if (days.size() < minObs) {
            return empty(days.size(), "insufficient history: " + days.size() + " days, need " + minObs);
        }
        // Strict coverage: instrument must have a return on every day.
        List<String> covered = new ArrayList<>();
        BigDecimal coveredExp = BigDecimal.ZERO;
        BigDecimal skippedExp = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> e : exposuresUsd.entrySet()) {
            boolean full = days.stream().allMatch(d -> d.returns().containsKey(e.getKey()));
            if (full) {
                covered.add(e.getKey());
                coveredExp = coveredExp.add(e.getValue().abs());
            } else {
                skippedExp = skippedExp.add(e.getValue().abs());
            }
        }
        if (covered.isEmpty()) {
            return new VarResult(money(0), money(0), money(0), days.size(),
                    money(0), skippedExp.setScale(2, RoundingMode.HALF_UP),
                    "no position has full return history yet");
        }

        double[] pnl = new double[days.size()];
        for (int d = 0; d < days.size(); d++) {
            double sum = 0;
            for (String id : covered) {
                sum += exposuresUsd.get(id).doubleValue() * days.get(d).returns().get(id);
            }
            pnl[d] = sum;
        }
        java.util.Arrays.sort(pnl); // ascending: worst loss first

        // Honest labeling (ADR-0041): below 100 observations, ⌊0.01·K⌋ = 0 — the "99% quantile"
        // is literally the sample's worst day. Say so rather than let it read as calibrated.
        String note = days.size() < 100
                ? "VaR99 = worst observed day (window " + days.size() + " < 100) — indicative only"
                : null;
        return new VarResult(
                lossAt(pnl, 0.05), tailMean(pnl, 0.05), lossAt(pnl, 0.01),
                days.size(), coveredExp.setScale(2, RoundingMode.HALF_UP),
                skippedExp.setScale(2, RoundingMode.HALF_UP), note);
    }

    /** −pnl at the ⌊α·K⌋-th ascending index, floored at 0 (a gain quantile is zero risk). */
    private static BigDecimal lossAt(double[] sortedPnl, double alpha) {
        int idx = (int) Math.floor(alpha * sortedPnl.length);
        return money(Math.max(0, -sortedPnl[Math.min(idx, sortedPnl.length - 1)]));
    }

    /** −mean of the pnl observations at or below the α-quantile index (expected shortfall). */
    private static BigDecimal tailMean(double[] sortedPnl, double alpha) {
        int idx = (int) Math.floor(alpha * sortedPnl.length);
        double sum = 0;
        for (int i = 0; i <= Math.min(idx, sortedPnl.length - 1); i++) {
            sum += sortedPnl[i];
        }
        return money(Math.max(0, -sum / (idx + 1)));
    }

    private static VarResult empty(int observations, String note) {
        return new VarResult(money(0), money(0), money(0), observations, money(0), money(0), note);
    }

    /** The money boundary: analytics double → exact decimal (invariant 1). */
    private static BigDecimal money(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }
}
