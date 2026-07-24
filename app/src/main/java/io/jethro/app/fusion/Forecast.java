package io.jethro.app.fusion;

/**
 * A single source's normalised, bounded view on one instrument (ADR-0055 phase 2). Every source —
 * deterministic strategy, AI hypothesis, corroborated social, learned label — is mapped onto ONE
 * dimensionless scale so forecasts can be compared and combined: Carver's convention (Systematic
 * Trading, 2015) — a forecast in {@code [-CAP, +CAP]} with expected absolute value {@code TARGET_ABS},
 * capping to support diversification and tame estimation error and negative skew from extreme readings.
 *
 * <p>{@code value} is a dimensionless conviction, never a size, price, or any number into PnL/risk
 * (ADR-0016 / invariant 7): the deterministic layer downstream turns the combined forecast into a
 * target position. Sign is direction (+ long / − short); magnitude is strength. Zero = no view.
 */
public record Forecast(String source, String instrument, double value) {

    /** Carver's forecast cap: readings beyond this are clipped (diversification + skew control). */
    public static final double CAP = 20.0;

    /** Target expected absolute forecast — sources are scaled so a typical |reading| ≈ this. */
    public static final double TARGET_ABS = 10.0;

    /** Builds a forecast, clamping the raw value into {@code [-CAP, +CAP]} (NaN → 0, no view). */
    public static Forecast of(String source, String instrument, double rawValue) {
        return new Forecast(source, instrument, clamp(rawValue));
    }

    /** Clamps a raw forecast into {@code [-CAP, +CAP]}; NaN/infinite → 0. */
    public static double clamp(double v) {
        if (!Double.isFinite(v)) {
            return 0.0;
        }
        return Math.max(-CAP, Math.min(CAP, v));
    }

    public boolean hasView() {
        return value != 0.0;
    }
}
