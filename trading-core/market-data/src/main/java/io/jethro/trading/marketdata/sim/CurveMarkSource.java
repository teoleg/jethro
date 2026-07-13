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

    /** Pseudo-instrument ids the tenor zero rates are published under (risk-pnl routes these
     *  to curve calibration, never to positions). */
    String[] TENOR_IDS = {"USD.SOFR.1Y", "USD.SOFR.2Y", "USD.SOFR.5Y", "USD.SOFR.10Y", "USD.SOFR.30Y"};

    /** Tenor grid in years, aligned to {@link #TENOR_IDS}. */
    double[] TENORS = {1, 2, 5, 10, 30};

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

    /** Zero rate for {@link #TENOR_IDS}[i], in percent as a scaled long (1e-6 units). */
    long rateScaledPercent(int tenorIndex);

    /** Par swap rate for {@link #SWAP_IDS}[i], in percent as a scaled long (1e-6 units). */
    long swapParScaledPercent(int swapIndex);

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
