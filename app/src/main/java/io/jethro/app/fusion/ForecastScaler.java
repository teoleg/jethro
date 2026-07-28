package io.jethro.app.fusion;

/**
 * Pure Carver-style forecast scaling (ADR-0055 phase 2). Two shapes:
 *
 * <ul>
 *   <li><b>Continuous</b> {@link #scale}: a raw signed reading (e.g. a z-score) is rescaled so its
 *       expected absolute value becomes {@link Forecast#TARGET_ABS}, then capped — the standard
 *       forecast-scalar step (scaled = raw × TARGET_ABS / E|raw|).</li>
 *   <li><b>Ordinal</b> {@link #stepped}: a coarse signed level (e.g. LOW/MED/HIGH → ±1/±2/±3) maps to
 *       evenly spaced magnitudes, for sources that emit a category rather than a number.</li>
 * </ul>
 *
 * <p>{@code E|raw|} (the expected absolute raw reading) is a source-specific scalar: a modelling
 * constant that SHOULD be estimated from the ADR-0055 phase-1 telemetry, not guessed — until then it is
 * a stated placeholder. Nothing here is a size or a price (ADR-0016 / invariant 7).
 */
public final class ForecastScaler {

    private ForecastScaler() {
    }

    /** Continuous: rescale {@code rawSigned} so E|reading| ≈ TARGET_ABS, then cap. */
    public static double scale(double rawSigned, double expectedAbsRaw) {
        return Forecast.clamp(claim(rawSigned, expectedAbsRaw));
    }

    /**
     * The same rescaling WITHOUT the cap — what the source claims before clipping. {@link ForecastScalars}
     * measures the claim rather than the capped forecast (ADR-0092): a clipped reading has already lost
     * the magnitude the measurement needs. Unbounded by design; callers cap after rescaling.
     */
    public static double claim(double rawSigned, double expectedAbsRaw) {
        if (!(expectedAbsRaw > 0) || !Double.isFinite(rawSigned)) {
            return 0.0;
        }
        return rawSigned * (Forecast.TARGET_ABS / expectedAbsRaw);
    }

    /** Ordinal: a signed level (…,-2,-1,0,+1,+2,…) × {@code stepPerLevel}, capped. */
    public static double stepped(int signedLevel, double stepPerLevel) {
        return Forecast.clamp(signedLevel * stepPerLevel);
    }
}
