package io.muniworld.curve;

/**
 * Sanity gate for benchmark curve days (owner directive: "be aware of bad benchmark curves").
 *
 * <p>A corrupt curve row — a mis-parsed column, a fat-fingered vintage, a unit slip — would silently poison
 * every OAS discounted on it. So every curve day passes this gate TWICE: at ingest (a failing day is
 * QUARANTINED — counted and reported, never stored, ADR-0011) and again at read time in the OAS path (a
 * failing stored day refuses the calculation by name, in case the gate tightens after data landed).
 *
 * <p>The band is judged at the benchmark tenors (1y, 10y, 30y): every zero must lie within
 * [{@link #MIN_RATE_PCT}, {@link #MAX_RATE_PCT}] percent. Bounds are Tier J
 * (docs/model-assumptions.md) — reasoned from the published record, not invented precision:
 * <ul>
 *   <li><b>+35%:</b> the highest 1-year Treasury zero in the GSW file's own 60+ years is ~17% (1981);
 *       35% is double the worst ever printed — beyond it, the row is corrupt, not history.</li>
 *   <li><b>−2%:</b> Treasury zeros have printed marginally negative (bills, 2015/2020, a few bp);
 *       −2% is far below any US print — beyond it, the row is corrupt.</li>
 * </ul>
 * Widening the band admits more of a corrupt file before refusal; it never changes a passing value.
 */
public final class CurveSanity {

    public static final double MIN_RATE_PCT = -2.0;
    public static final double MAX_RATE_PCT = 35.0;

    /** The tenors the gate inspects — short, belly, long; a unit slip or column shift fails all three. */
    private static final double[] CHECK_TENORS = {1, 10, 30};

    private CurveSanity() {
    }

    /** True when every checked zero of this curve lies inside the plausibility band. */
    public static boolean plausible(NelsonSiegelSvensson curve) {
        for (double t : CHECK_TENORS) {
            double pct = curve.zeroRate(t).movePointRight(2).doubleValue();
            if (!(pct >= MIN_RATE_PCT && pct <= MAX_RATE_PCT)) {   // catches NaN too
                return false;
            }
        }
        return true;
    }

    /** The failure, named for the refusal/quarantine message; null when the curve is fine. */
    public static String objection(NelsonSiegelSvensson curve) {
        for (double t : CHECK_TENORS) {
            double pct = curve.zeroRate(t).movePointRight(2).doubleValue();
            if (!(pct >= MIN_RATE_PCT && pct <= MAX_RATE_PCT)) {
                return "zero(" + (int) t + "y) = " + pct + "% is outside the plausibility band ["
                        + MIN_RATE_PCT + "%, " + MAX_RATE_PCT + "%]";
            }
        }
        return null;
    }
}
