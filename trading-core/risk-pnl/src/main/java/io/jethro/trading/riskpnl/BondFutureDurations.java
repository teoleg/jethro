package io.jethro.trading.riskpnl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;

/**
 * Live modified duration for the Treasury futures — the input that makes bond-future DV01
 * <b>dynamic</b> instead of a static reference-data constant. Each future keys off its CTD's
 * tenor sector (ZT→2Y, ZF→5Y, ZN→10Y, ZB→30Y); duration is the closed-form modified duration
 * of a semiannual PAR bond at the LIVE Treasury par yield for that tenor:
 *
 * <pre>  D(y, T) = (1/y) · (1 − (1 + y/2)^(−2T))</pre>
 *
 * Worked: y = 4.5%, T = 10 → (1/0.045)(1 − 1.0225⁻²⁰) = 22.22 × 0.3592 ≈ 7.98 — and at
 * y = 1.5% the same 10Y is ≈ 9.25: duration extends as yields fall, shortens as they rise,
 * which a static constant misses across a 2022-style ±300bp regime. Falls back to the
 * refdata modified duration when the curve hasn't quoted (or y ≤ 0) — disclosed by
 * {@link #source}, never guessed.
 *
 * <p>Analytics double internally, {@link BigDecimal} at the boundary — duration is a
 * sensitivity parameter, not money (invariant 1 applies where it's multiplied into P&amp;L).
 * CONVENTION: par-bond proxy for the CTD — no delivery basket / conversion-factor model
 * (that refinement is a real CTD model; tracked as follow-up, not faked here).
 */
public final class BondFutureDurations {

    /** Future → the key tenor (years) its CTD sector tracks. */
    private static final Map<String, Double> KEY_TENOR_YEARS =
            Map.of("ZT", 2.0, "ZF", 5.0, "ZN", 10.0, "ZB", 30.0);

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

    private Optional<BigDecimal> liveDuration(String instrumentId) {
        Double tenor = KEY_TENOR_YEARS.get(instrumentId);
        if (tenor == null || curve == null) {
            return Optional.empty();
        }
        for (TreasuryCurveView.TsyPoint p : curve.snapshot()) {
            if (p.tenorYears() == tenor && p.parYield() > 1e-4) {
                return Optional.of(parBondModifiedDuration(p.parYield(), tenor));
            }
        }
        return Optional.empty();
    }

    /** D(y,T) = (1/y)(1 − (1+y/2)^(−2T)), y as a fraction. Worked: (0.045, 10) → 7.9819. */
    public static BigDecimal parBondModifiedDuration(double yieldFraction, double tenorYears) {
        double d = (1.0 / yieldFraction)
                * (1.0 - Math.pow(1.0 + yieldFraction / 2.0, -2.0 * tenorYears));
        return BigDecimal.valueOf(d).setScale(4, RoundingMode.HALF_UP);
    }
}
