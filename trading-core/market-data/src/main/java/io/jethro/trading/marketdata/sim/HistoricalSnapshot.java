package io.jethro.trading.marketdata.sim;

import java.util.List;
import java.util.SplittableRandom;

/**
 * A snapshot of real daily OHLCV history for the sim universe (ADR-0032): per-instrument close
 * and volume series on a shared date axis, so the cross-section of one day lines up across
 * instruments. The {@link HistoricalBootstrapSimulator} resamples these real return+volume
 * vectors in blocks, which is what makes the sim tape carry real fat tails, vol clustering and
 * cross-asset correlations instead of a parametric approximation.
 *
 * <p>Provenance is explicit: {@link #source()} is {@code "yahoo"} for a real pull or
 * {@code "synthetic"} for the generated seed used offline/CI (never real data mislabelled). Prices
 * are held as {@code double} in real units — the engine works in ratios and scales to
 * {@code long} 1e-6 only at emission; these are statistical inputs, not a money ledger (invariant
 * 1 governs the emitted marks, which stay scaled-long).
 */
public final class HistoricalSnapshot {

    private final String source;
    private final String[] ids;
    private final double[][] close;   // [instrument][day], real price units, strictly positive
    private final long[][] volume;    // [instrument][day], raw shares/contracts (>= 0)
    private final int days;

    public HistoricalSnapshot(String source, List<String> instrumentIds,
                              double[][] close, long[][] volume) {
        if (instrumentIds.isEmpty()) {
            throw new IllegalArgumentException("snapshot needs at least one instrument");
        }
        int n = instrumentIds.size();
        if (close.length != n || volume.length != n) {
            throw new IllegalArgumentException("close/volume rows must align with instruments");
        }
        int d = close[0].length;
        if (d < 2) {
            throw new IllegalArgumentException("snapshot needs at least 2 days to form a return");
        }
        for (int i = 0; i < n; i++) {
            if (close[i].length != d || volume[i].length != d) {
                throw new IllegalArgumentException("all series must share the date axis (" + d + " days)");
            }
            for (int t = 0; t < d; t++) {
                if (!(close[i][t] > 0)) {
                    throw new IllegalArgumentException(
                            "non-positive close for " + instrumentIds.get(i) + " at day " + t);
                }
                if (volume[i][t] < 0) {
                    throw new IllegalArgumentException("negative volume for " + instrumentIds.get(i));
                }
            }
        }
        this.source = source == null || source.isBlank() ? "unknown" : source;
        this.ids = instrumentIds.toArray(String[]::new);
        this.close = close;
        this.volume = volume;
        this.days = d;
    }

    public String source() {
        return source;
    }

    public boolean synthetic() {
        return "synthetic".equals(source);
    }

    public List<String> instrumentIds() {
        return List.of(ids);
    }

    /** Number of days on the shared axis (returns available: {@code days() - 1}). */
    public int days() {
        return days;
    }

    /** Close for instrument index {@code i} on day {@code t} (real price units). */
    public double close(int i, int t) {
        return close[i][t];
    }

    /** Raw volume for instrument index {@code i} on day {@code t}. */
    public long volume(int i, int t) {
        return volume[i][t];
    }

    /** Most recent close (the natural start level for a bootstrapped path). */
    public double lastClose(int i) {
        return close[i][days - 1];
    }

    /**
     * Per-instrument daily log-return matrix {@code [instrument][day]}, length {@code days()-1};
     * column {@code t} is the SAME calendar day across instruments, so a block draw preserves the
     * real cross-sectional correlation.
     */
    public double[][] logReturns() {
        double[][] r = new double[ids.length][days - 1];
        for (int i = 0; i < ids.length; i++) {
            for (int t = 0; t < days - 1; t++) {
                r[i][t] = Math.log(close[i][t + 1] / close[i][t]);
            }
        }
        return r;
    }

    /**
     * A deterministic, clearly-labelled <b>synthetic</b> seed — geometric returns from each
     * instrument's annualized vol and a lognormal volume around a base — so the engine, backtests
     * and CI have a snapshot offline. NOT real history (it can't reproduce real tails/clustering);
     * a real pull ({@code source="yahoo"}) replaces it. Same seed ⇒ same seed snapshot (ADR-0009).
     */
    public static HistoricalSnapshot synthetic(long seed, List<String> instrumentIds,
                                               double[] startPrices, double[] annualVols,
                                               long[] baseDailyVolumes, int days) {
        int n = instrumentIds.size();
        if (startPrices.length != n || annualVols.length != n || baseDailyVolumes.length != n) {
            throw new IllegalArgumentException("synthetic inputs must align with instruments");
        }
        SplittableRandom rnd = new SplittableRandom(seed);
        double dailySigmaScale = 1.0 / Math.sqrt(252.0);
        double[][] close = new double[n][days];
        long[][] volume = new long[n][days];
        for (int i = 0; i < n; i++) {
            double sigma = annualVols[i] * dailySigmaScale;
            double price = startPrices[i];
            for (int t = 0; t < days; t++) {
                if (t > 0) {
                    price *= Math.exp(-0.5 * sigma * sigma + sigma * rnd.nextGaussian());
                }
                close[i][t] = price;
                // Lognormal volume around the base (fat right tail, like real turnover).
                double v = baseDailyVolumes[i] * Math.exp(0.4 * rnd.nextGaussian() - 0.08);
                volume[i][t] = Math.max(1, Math.round(v));
            }
        }
        return new HistoricalSnapshot("synthetic", instrumentIds, close, volume);
    }
}
