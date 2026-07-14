package io.jethro.trading.marketdata.sim;

/**
 * The USD rates curve as a source of <b>market data marks</b> — the shape the market-data
 * adapters emit each pass, decoupled from whether the curve is simulated or real. Two
 * implementations: {@link CurveFactorSimulator} (seedable factor sim, the local-dev/CI
 * default) and {@link RealTreasuryCurve} (live Treasury yields, ADR-0024). Adapters call
 * these identically, so a real curve slots in with no downstream change; everything
 * downstream (Strata calibration, DV01, swap PV, scenarios, the curve display) reprices on
 * whichever curve is active.
 *
 * <p>Marks are quoted in <b>percent</b> as scaled longs (1e-6 units), like every price
 * (invariant 1 at the boundary lives in risk-pnl calibration, not here — this is raw data).
 */
public interface CurveMarkSource {

    /** Pseudo-instrument ids the SOFR tenor zero rates are published under (risk-pnl routes
     *  these to curve calibration, never to positions). */
    String[] TENOR_IDS = {"USD.SOFR.1Y", "USD.SOFR.2Y", "USD.SOFR.5Y", "USD.SOFR.10Y", "USD.SOFR.30Y"};

    /** Pseudo-instrument ids of the US TREASURY par-yield curve — a DISTINCT curve from SOFR
     *  (the difference is the swap spread; Treasury futures key off THIS one). */
    String[] TSY_TENOR_IDS = {"USD.TSY.1Y", "USD.TSY.2Y", "USD.TSY.5Y", "USD.TSY.10Y", "USD.TSY.30Y"};

    /** Tenor grid in years, aligned to {@link #TENOR_IDS} / {@link #TSY_TENOR_IDS}. */
    double[] TENORS = {1, 2, 5, 10, 30};

    /**
     * Stylized TSY−SOFR spread per node, in basis points (Treasury par yield ABOVE the SOFR
     * swap rate — the negative-swap-spread era, 2024-26 magnitudes: ~−8bp at 1Y widening to
     * ~−65bp at 30Y quoted as swap−TSY). CONVENTION, stated per the finance-math rule: a
     * demo constant, refined by live data when a real source carries both curves.
     */
    double[] TSY_SPREAD_BP = {8, 15, 25, 38, 65};

    /** Tradeable swaps quoted off the curve (par rate in percent, 1 lot = $1M, BUY = pay fixed). */
    String[] SWAP_IDS = {"USD_IRS_5Y", "USD_IRS_10Y"};

    /** Tenor in years for each of {@link #SWAP_IDS}. */
    int[] SWAP_TENOR_YEARS = {5, 10};

    /** Advances the curve one tick under the market regime (a real curve ignores this — its
     *  levels change only when refreshed from the provider). */
    void step(MarketRegime regime, int shockSign);

    /** Convenience: a CALM, no-shock step. */
    default void step() {
        step(MarketRegime.CALM, 0);
    }

    /**
     * Advances the curve by externally supplied level/slope deltas (fractions) — how the
     * correlated factor simulator (ADR-0026) keeps rates in concert with equities/FX. A REAL
     * curve ignores the deltas (its levels come from the provider, never from sim factors);
     * the factor-sim curve applies them.
     */
    default void applyExternalStep(double dLevel, double dSlope) {
        step();
    }

    /** Zero rate for {@link #TENOR_IDS}[i], in percent as a scaled long (1e-6 units). */
    long rateScaledPercent(int tenorIndex);

    /** Par swap rate for {@link #SWAP_IDS}[i], in percent as a scaled long (1e-6 units). */
    long swapParScaledPercent(int swapIndex);

    /** US Treasury par yield for {@link #TSY_TENOR_IDS}[i], in percent as a scaled long.
     *  Default: the SOFR node plus the stylized swap spread; sources with their own Treasury
     *  data (the real feed) or a stochastic basis (the factor sim) override. */
    default long tsyRateScaledPercent(int tenorIndex) {
        return rateScaledPercent(tenorIndex) + Math.round(TSY_SPREAD_BP[tenorIndex] * 0.01 * 1_000_000);
    }

    /** True if this instrument's price derives from the curve (sim-only Treasury futures);
     *  real feeds price futures from the market, so the default is false. */
    default boolean isLinked(String instrumentId) {
        return false;
    }

    /** Curve-implied futures price (scaled 1e-6) — only meaningful when {@link #isLinked}. */
    default long linkedPriceScaled(String instrumentId) {
        throw new UnsupportedOperationException("not a curve-linked instrument: " + instrumentId);
    }
}
