package io.jethro.trading.riskpnl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;

/**
 * Live modified duration for the Treasury futures — the input that makes bond-future DV01
 * <b>dynamic</b> instead of a static reference-data constant. The CTD maturity comes from
 * the contract's PUBLISHED CME deliverable window plus the classic conversion-factor rule:
 * CME conversion factors price every deliverable at a 6% yield, so with market yields
 * BELOW 6% the cheapest-to-deliver is the SHORT-maturity end of the window (low duration),
 * and above 6% the LONG end. Duration is then the closed-form modified duration of a
 * semiannual PAR bond at the LIVE Treasury par yield interpolated at that CTD maturity:
 *
 * <pre>  D(y, T_ctd) = (1/y) · (1 − (1 + y/2)^(−2·T_ctd))</pre>
 *
 * Worked: ZN's deliverable window is 6.5–10y; at y = 4.5% (&lt; 6%) the CTD sits at the
 * 6.5y end → D(0.045, 6.5) = 22.22 × (1 − 1.0225⁻¹³) = <b>5.58</b> — materially lower than
 * the naive 10y par-bond figure (7.98), which is exactly the error a real CTD selection
 * removes. Duration still breathes with the yield level, and the 6% pivot flips the window
 * end if yields ever cross it. Falls back to the refdata modified duration when the curve
 * hasn't quoted (or y ≤ 0) — disclosed by {@link #source}, never guessed.
 *
 * <p>Analytics double internally, {@link BigDecimal} at the boundary — duration is a
 * sensitivity parameter, not money (invariant 1 applies where it's multiplied into P&amp;L).
 * Stated limits: the window ends and the 6% rule are the real contract mechanics, but the
 * true CTD needs the actual deliverable basket (issue-level coupons/maturities and repo) —
 * per-issue selection remains open until real bond reference data exists.
 */
public final class BondFutureDurations {

    /** Published CME deliverable maturity windows (years) per future. */
    record DeliverableWindow(double shortYears, double longYears) {
    }

    private static final Map<String, DeliverableWindow> DELIVERABLE = Map.of(
            "ZT", new DeliverableWindow(1.75, 2.0),
            "ZF", new DeliverableWindow(4.17, 5.25),
            "ZN", new DeliverableWindow(6.5, 10.0),
            "ZB", new DeliverableWindow(15.0, 25.0));

    /** CME conversion factors assume a 6% yield: below it the SHORT window end is CTD. */
    private static final double CF_PIVOT_YIELD = 0.06;

    private final TreasuryCurveView curve; // nullable → static refdata fallback only
    private final InstrumentRefSource refs;

    public BondFutureDurations(TreasuryCurveView curve, InstrumentRefSource refs) {
        this.curve = curve;
        this.refs = refs;
    }

    /** Static-refdata-only view (no live curve) — the pre-existing behaviour. */
    public static BondFutureDurations staticOnly(InstrumentRefSource refs) {
        return new BondFutureDurations(null, refs);
    }

    /**
     * Modified duration for an instrument: live par-bond duration at the current Treasury
     * yield when this is a known bond future and the curve has quoted; else the refdata
     * static duration; empty when neither exists (callers must skip, never guess).
     */
    public Optional<BigDecimal> modifiedDuration(String instrumentId) {
        Optional<BigDecimal> live = liveDuration(instrumentId);
        if (live.isPresent()) {
            return live;
        }
        return refs.find(instrumentId).map(InstrumentRef::modDuration);
    }

    /** Where the duration came from — for surfaces that disclose live vs static. */
    public String source(String instrumentId) {
        return liveDuration(instrumentId).isPresent()
                ? "live par-bond duration @ Treasury curve" : "refdata static duration";
    }

    /**
     * The maturity (years) where this bond future's rate risk actually sits — the CTD
     * maturity under the same 6% conversion-factor rule as {@link #modifiedDuration}
     * (live yield decides the window end; without a live curve, the SHORT end — the
     * below-6% world every current market is in — disclosed, not guessed silently).
     * Empty for instruments that aren't known bond futures. Used to allocate a future's
     * DV01 onto the curve's tenor buckets (quant-engine step 4).
     */
    public Optional<Double> keyTenorYears(String instrumentId) {
        DeliverableWindow window = DELIVERABLE.get(instrumentId);
        if (window == null) {
            return Optional.empty();
        }
        return Optional.of(ctd(instrumentId).map(CtdPoint::maturityYears)
                .orElse(window.shortYears()));
    }

    private Optional<BigDecimal> liveDuration(String instrumentId) {
        return ctd(instrumentId).map(c -> parBondModifiedDuration(c.yield(), c.maturityYears()));
    }

    /** The live CTD point (yield at the CTD maturity) — shared by duration and full reval. */
    private record CtdPoint(double yield, double maturityYears) {
    }

    private Optional<CtdPoint> ctd(String instrumentId) {
        DeliverableWindow window = DELIVERABLE.get(instrumentId);
        if (window == null || curve == null) {
            return Optional.empty();
        }
        var points = curve.snapshot();
        if (points.isEmpty()) {
            return Optional.empty();
        }
        // First pass at the long end's yield decides which window end is CTD (6% rule);
        // then the final duration prices at the yield interpolated AT the CTD maturity.
        double longEndYield = interpolatedYield(points, window.longYears());
        if (longEndYield <= 1e-4) {
            return Optional.empty();
        }
        double ctdMaturity = longEndYield < CF_PIVOT_YIELD ? window.shortYears() : window.longYears();
        double y = interpolatedYield(points, ctdMaturity);
        if (y <= 1e-4) {
            return Optional.empty();
        }
        return Optional.of(new CtdPoint(y, ctdMaturity));
    }

    /**
     * FULL-REVALUATION price change (fraction of par) of the CTD par bond under a parallel
     * yield shift (quant-engine step 2 remainder): re-prices the bond at the shocked yield
     * instead of extrapolating −D·Δy, so the result carries <b>convexity</b> — at ±100bp on
     * ZB (D≈10.8) the linear number is wrong by ~7 points of the move, and rates DOWN gains
     * more than rates UP loses, which linear can't show. Empty without a live curve (the
     * engine falls back to first-order duration, disclosed). Empty too if the shift would
     * take the yield non-positive (no sane reprice — callers fall back, never guess).
     */
    public Optional<BigDecimal> priceChangeUnderShock(String instrumentId, BigDecimal shiftBps) {
        return ctd(instrumentId).flatMap(c -> {
            double shifted = c.yield() + shiftBps.doubleValue() / 10_000.0;
            if (shifted <= 1e-4) {
                return Optional.empty();
            }
            return Optional.of(parBondPriceChange(c.yield(), shifted, c.maturityYears()));
        });
    }

    /**
     * ΔP of a semiannual par bond (coupon = base yield {@code y}, price 1 at base) re-priced
     * at {@code yShifted}: P = (y/y')(1 − (1+y'/2)^(−2T)) + (1+y'/2)^(−2T), ΔP = P − 1.
     * Worked (ZN CTD 6.5y, y 4.5%): +100bp → −0.0540350543, −100bp → +0.0576882049 —
     * linear ±0.055818 sits between them, the convexity asymmetry. Fraction at scale 10.
     */
    public static BigDecimal parBondPriceChange(double y, double yShifted, double tenorYears) {
        double disc = Math.pow(1.0 + yShifted / 2.0, -2.0 * tenorYears);
        double price = (y / yShifted) * (1.0 - disc) + disc;
        return BigDecimal.valueOf(price - 1.0).setScale(10, RoundingMode.HALF_UP);
    }

    /** Linear par-yield interpolation on the live tenor grid (flat extrapolation at the ends). */
    private static double interpolatedYield(java.util.List<TreasuryCurveView.TsyPoint> points, double t) {
        TreasuryCurveView.TsyPoint below = null;
        TreasuryCurveView.TsyPoint above = null;
        for (TreasuryCurveView.TsyPoint p : points) {
            if (p.tenorYears() <= t && (below == null || p.tenorYears() > below.tenorYears())) {
                below = p;
            }
            if (p.tenorYears() >= t && (above == null || p.tenorYears() < above.tenorYears())) {
                above = p;
            }
        }
        if (below == null) {
            return above != null ? above.parYield() : 0;
        }
        if (above == null || above.tenorYears() == below.tenorYears()) {
            return below.parYield();
        }
        double w = (t - below.tenorYears()) / (above.tenorYears() - below.tenorYears());
        return below.parYield() + w * (above.parYield() - below.parYield());
    }

    /** D(y,T) = (1/y)(1 − (1+y/2)^(−2T)), y as a fraction. Worked: (0.045, 10) → 7.9819. */
    public static BigDecimal parBondModifiedDuration(double yieldFraction, double tenorYears) {
        double d = (1.0 / yieldFraction)
                * (1.0 - Math.pow(1.0 + yieldFraction / 2.0, -2.0 * tenorYears));
        return BigDecimal.valueOf(d).setScale(4, RoundingMode.HALF_UP);
    }
}
