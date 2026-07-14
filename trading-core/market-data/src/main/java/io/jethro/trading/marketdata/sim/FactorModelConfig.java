package io.jethro.trading.marketdata.sim;

import java.util.List;

/**
 * Calibration for the correlated cross-asset factor simulator (ADR-0026) — plain records so
 * the market-data module stays dependency-free; the app assembly parses the checked-in
 * {@code sim-calibration.json} (curated from real long-run market statistics, refreshable by
 * {@code scripts/calibrate_sim.py} from free daily history) into this shape.
 *
 * <p><b>Factor order convention (fixed):</b> index 0 = EQUITY (global equity market return),
 * 1 = RATES_LEVEL (parallel yield change), 2 = RATES_SLOPE (long−short yield change),
 * 3 = USD (dollar return). Every correlation matrix in {@link RegimeSpec} uses this order.
 *
 * <p>Instrument model: {@code rᵢ = βᵢ,eq·f_eq + βᵢ,usd·f_usd + idioᵢ}, with idio vol derived
 * so the instrument's TOTAL annual vol matches {@code annualVol} (systematic variance is
 * subtracted; floored at 10% of total so no name is ever purely deterministic). Treasury
 * futures and swaps carry no spec — they price FROM the curve, whose level/slope are driven
 * by the RATES factors, which is what keeps rates in concert with everything else.
 */
public record FactorModelConfig(
        /** Annual vol of the EQUITY factor (e.g. 0.16 = S&P-like 16%). */
        double equityFactorVolAnnual,
        /** Annual vol of the USD factor (e.g. 0.07). */
        double usdFactorVolAnnual,
        /** Daily vol of the RATES level factor, in basis points (e.g. 5.0). */
        double ratesLevelVolBpPerDay,
        /** Daily vol of the RATES slope factor, in basis points (e.g. 2.0). */
        double ratesSlopeVolBpPerDay,
        /** Student-t degrees of freedom for the joint innovation (≈5 → fat tails). */
        double tDegreesOfFreedom,
        List<InstrumentSpec> instruments,
        /** Regimes; list order defines the row/column indices of {@link #transitionPerDay}. */
        List<RegimeSpec> regimes,
        /** Row-stochastic DAILY regime transition matrix, indexed like {@link #regimes}. */
        double[][] transitionPerDay) {

    /** One price-quoted instrument (equity, index future, FX pair) — rates ride the curve. */
    public record InstrumentSpec(
            String id,
            /** Total annual vol of the instrument (e.g. 0.28 for AAPL). */
            double annualVol,
            /** Loading on the EQUITY factor (index future ≈ 1, single names ≈ 1.1–1.3). */
            double betaEquity,
            /** Loading on the USD factor (EURUSD −1, USDJPY +1, equities ≈ 0). */
            double betaUsd) {
    }

    /**
     * One market regime. {@code name} must match a {@link MarketRegime} constant.
     * Correlations use the fixed factor order [EQUITY, RATES_LEVEL, RATES_SLOPE, USD];
     * the matrix must be symmetric with a unit diagonal (Cholesky-decomposed at load).
     */
    public record RegimeSpec(
            String name,
            /** Annualized drift of the EQUITY factor in this regime (e.g. −0.30 in a bear). */
            double equityDriftAnnual,
            /** Drift of the RATES level in basis points per day (e.g. +8 in an inflation shock). */
            double ratesDriftBpPerDay,
            /** Annualized drift of the USD factor (risk-off ≈ +0.25 → dollar bid). */
            double usdDriftAnnual,
            /** Vol multiplier applied to every factor and idio in this regime. */
            double volMultiple,
            double[][] factorCorrelation) {
    }

    /** Index of a regime by name, or -1. */
    public int regimeIndex(String name) {
        for (int i = 0; i < regimes.size(); i++) {
            if (regimes.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }
}
