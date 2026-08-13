package io.muniworld.curve;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Short-rate volatility for the lattice, MEASURED rather than assumed (ADR-0017 §3).
 *
 * <p>There is no free implied-vol surface for rates — Cboe discontinued SRVIX in 2022 and TYVIX with it,
 * and MOVE is licensed — so sigma is not taken from option prices. It is the REALIZED volatility of the
 * benchmark short rate, computed from the GSW daily history already ingested (60+ years of official Fed
 * data). The only judgement is the lookback window, and that is declared, never buried.
 *
 * <p>Two parameterisations, because the lattice family decides which one is meaningful:
 * <ul>
 *   <li><b>normal / Hull-White:</b> {@code sigma_bp = stdev(dr) * sqrt(252)}, r in basis points</li>
 *   <li><b>lognormal / BDT / Black-Karasinski</b> (Kalotay co-authored BDT):
 *       {@code sigma_ln = stdev(d ln r) * sqrt(252)}</li>
 * </ul>
 *
 * <p><b>CONVENTION:</b> annualisation uses 252 trading days — the standard convention for a series observed
 * on business days only, which the Fed's curve is. Sample standard deviation uses the n−1 (Bessel)
 * denominator: these are a sample of a process, not a population.
 *
 * <p><b>Zero and negative rates.</b> {@code ln r} is undefined at r <= 0 and explodes as r approaches 0
 * (the 1-year zero reached a few basis points in 2020-21). Observations at or below {@link #LN_FLOOR_BP}
 * are EXCLUDED from the lognormal estimate and COUNTED, never silently dropped — the count travels with the
 * result so a lognormal sigma fitted on a thinned sample is visible as such. The normal estimate uses every
 * observation; it has no such restriction, which is one reason to prefer it near the zero bound.
 *
 * <p><b>Known bias (ADR-0017 §3).</b> Tax-exempt rates are materially less volatile than Treasuries, so a
 * Treasury-derived sigma OVERSTATES muni option value, which UNDERSTATES OAS on callables. That is the
 * conservative direction — it cannot make a callable look cheap when it is not — and it is the documented
 * stand-in until the muni curve has enough history to be its own underlying.
 */
public final class RealizedVol {

    /** Trading days per year — the annualisation convention for a business-day series. */
    public static final int TRADING_DAYS = 252;

    /** Rates at or below 1bp are excluded from the LOGNORMAL estimate (ln explodes); counted, not dropped. */
    public static final double LN_FLOOR_BP = 1.0;

    /** Volatility results are carried at 6dp — finer than any sigma is ever quoted. */
    public static final int VOL_SCALE = 6;

    private RealizedVol() {
    }

    /**
     * A volatility estimate and everything needed to judge it: the annualised sigma, how many differences
     * it was computed from, and how many observations the estimator had to exclude.
     *
     * @param sigma    annualised volatility — basis points per year (normal) or a proportion per year
     *                 (lognormal, so 0.15 = 15%)
     * @param observations number of first differences the estimate used
     * @param excluded number of observations excluded (lognormal only: rates at or below the floor)
     */
    public record Estimate(BigDecimal sigma, int observations, int excluded) {
    }

    /**
     * A sigma BAND, never a single hidden number (ADR-0017 §3): the 10th / 50th / 90th percentile of rolling
     * one-year realized vol across the available history. OAS is reported at all three; a bond whose OAS
     * holds across the band is a relative-value candidate, one whose OAS flips sign across it is a
     * volatility bet.
     */
    public record Band(BigDecimal p10, BigDecimal p50, BigDecimal p90, int windows) {
    }

    /**
     * Normal (Hull-White) sigma in basis points per year from a series of short rates in BASIS POINTS,
     * chronological.
     *
     * <p><b>Worked example</b> (encoded as a test): r = [100, 110, 100, 110, 100, 100] bp gives five
     * differences [+10, −10, +10, −10, 0] with mean 0; the squared deviations sum to 400, so the sample
     * variance is 400/(5−1) = 100 and the daily stdev is exactly 10 bp. Annualised:
     * 10 * sqrt(252) = 158.745079 bp/yr at 6dp.
     */
    public static Estimate normal(List<Double> ratesBp) {
        List<Double> diffs = new ArrayList<>();
        for (int i = 1; i < ratesBp.size(); i++) {
            diffs.add(ratesBp.get(i) - ratesBp.get(i - 1));
        }
        return new Estimate(annualisedStdev(diffs), diffs.size(), 0);
    }

    /**
     * Lognormal (BDT / Black-Karasinski) sigma as a proportion per year from a series of short rates in
     * BASIS POINTS, chronological. A difference is used only when BOTH of its endpoints are above
     * {@link #LN_FLOOR_BP}; every observation at or below the floor is counted into {@code excluded}.
     */
    public static Estimate lognormal(List<Double> ratesBp) {
        List<Double> diffs = new ArrayList<>();
        int excluded = 0;
        for (int i = 1; i < ratesBp.size(); i++) {
            double prev = ratesBp.get(i - 1);
            double cur = ratesBp.get(i);
            if (prev <= LN_FLOOR_BP || cur <= LN_FLOOR_BP) {
                excluded++;                     // counted and reported — never a silent drop
                continue;
            }
            diffs.add(Math.log(cur) - Math.log(prev));
        }
        return new Estimate(annualisedStdev(diffs), diffs.size(), excluded);
    }

    /**
     * The p10/p50/p90 band of rolling {@code windowDays}-observation realized vol over the whole series.
     * {@code estimator} selects normal or lognormal. Returns {@code null} when the history is shorter than
     * one window — a band that cannot be measured is reported as absent, never approximated (ADR-0011).
     */
    public static Band band(List<Double> ratesBp, int windowDays,
                            java.util.function.Function<List<Double>, Estimate> estimator) {
        if (windowDays < 2 || ratesBp.size() < windowDays) {
            return null;
        }
        List<BigDecimal> vols = new ArrayList<>();
        for (int end = windowDays; end <= ratesBp.size(); end++) {
            Estimate e = estimator.apply(ratesBp.subList(end - windowDays, end));
            if (e.observations() >= 2) {
                vols.add(e.sigma());
            }
        }
        if (vols.isEmpty()) {
            return null;
        }
        Collections.sort(vols);
        return new Band(percentile(vols, 10), percentile(vols, 50), percentile(vols, 90), vols.size());
    }

    /**
     * Nearest-rank percentile of a SORTED list — the unambiguous definition (no interpolation variant to
     * guess at): rank = ceil(p/100 * n), clamped into the list.
     */
    static BigDecimal percentile(List<BigDecimal> sorted, int p) {
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.min(Math.max(rank, 1), sorted.size()) - 1);
    }

    /**
     * Sample standard deviation (n−1) of the differences, annualised by sqrt(252), rounded ONCE at
     * {@link #VOL_SCALE}. Fewer than two differences cannot yield a sample stdev — that returns zero
     * observations upstream rather than a fabricated sigma.
     */
    private static BigDecimal annualisedStdev(List<Double> diffs) {
        if (diffs.size() < 2) {
            return BigDecimal.ZERO.setScale(VOL_SCALE, RoundingMode.HALF_UP);
        }
        double mean = 0;
        for (double d : diffs) {
            mean += d;
        }
        mean /= diffs.size();
        double sumSq = 0;
        for (double d : diffs) {
            sumSq += (d - mean) * (d - mean);
        }
        double daily = Math.sqrt(sumSq / (diffs.size() - 1));
        return BigDecimal.valueOf(daily * Math.sqrt(TRADING_DAYS))
                .setScale(VOL_SCALE, RoundingMode.HALF_UP);
    }
}
