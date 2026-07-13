package io.jethro.trading.marketdata.sim;

/**
 * A real USD rates curve driven by live Treasury yields (ADR-0024), as a drop-in
 * {@link CurveMarkSource} for the market-data adapters. Unlike the factor sim, its levels do
 * not evolve on ticks ({@link #step} is a no-op) — they change only when
 * {@link #update(double[])} is called by the provider fetcher (throttled, off the tick path).
 * Between updates the last good curve stands.
 *
 * <p>Zero rates are held per node ({@link CurveMarkSource#TENORS}, in fraction, e.g. 0.0421 =
 * 4.21%); {@link #zeroRate} linearly interpolates in year-space (flat beyond the ends), and
 * swap par rates are the standard annual-fixed par formula on those zeros — the same shape the
 * sim emits, so everything downstream (Strata calibration, DV01, swap PV, scenarios, the curve
 * display) reprices identically, just on real levels. Futures are NOT curve-linked here (a live
 * feed prices them from the market), so {@link #isLinked} is false.
 */
public final class RealTreasuryCurve implements CurveMarkSource {

    private static final double MIN_RATE = 0.0001; // 1bp floor — no negative demo rates

    /** Zero rates per {@link CurveMarkSource#TENORS} node, in fraction. Volatile: the fetcher
     *  swaps the whole array atomically; readers (adapter thread) see a consistent snapshot. */
    private volatile double[] zeros;

    /** @param initialZeros zero rates per {@link CurveMarkSource#TENORS} node, in fraction. */
    public RealTreasuryCurve(double[] initialZeros) {
        update(initialZeros);
    }

    /** Replaces the curve with a fresh set of node zero rates (fraction), aligned to
     *  {@link CurveMarkSource#TENORS}. A null/short array is ignored (keep the last good curve). */
    public void update(double[] nodeZeros) {
        if (nodeZeros == null || nodeZeros.length != TENORS.length) {
            return;
        }
        double[] next = new double[nodeZeros.length];
        for (int i = 0; i < nodeZeros.length; i++) {
            next[i] = Math.max(MIN_RATE, nodeZeros[i]);
        }
        this.zeros = next;
    }

    @Override
    public void step(MarketRegime regime, int shockSign) {
        // Real curve — levels come from the provider, not a random walk. Nothing to do per tick.
    }

    @Override
    public long rateScaledPercent(int tenorIndex) {
        return Math.round(zeros[tenorIndex] * 100 * 1_000_000);
    }

    @Override
    public long swapParScaledPercent(int swapIndex) {
        int years = SWAP_TENOR_YEARS[swapIndex];
        double annuity = 0.0;
        for (int i = 1; i <= years; i++) {
            annuity += discountFactor(i);
        }
        double par = (1.0 - discountFactor(years)) / annuity;
        return Math.round(par * 100 * 1_000_000);
    }

    /** Zero rate for a tenor in years (fraction), linear-interpolated across the nodes. */
    public double zeroRate(double tenorYears) {
        double[] z = zeros;
        if (tenorYears <= TENORS[0]) {
            return z[0];
        }
        if (tenorYears >= TENORS[TENORS.length - 1]) {
            return z[z.length - 1];
        }
        for (int i = 1; i < TENORS.length; i++) {
            if (tenorYears <= TENORS[i]) {
                double w = (tenorYears - TENORS[i - 1]) / (TENORS[i] - TENORS[i - 1]);
                return z[i - 1] + w * (z[i] - z[i - 1]);
            }
        }
        return z[z.length - 1];
    }

    private double discountFactor(double tenorYears) {
        return Math.exp(-zeroRate(tenorYears) * tenorYears);
    }
}
