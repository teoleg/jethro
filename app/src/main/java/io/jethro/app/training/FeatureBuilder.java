package io.jethro.app.training;

import java.util.ArrayList;
import java.util.List;

/**
 * Point-in-time feature + label builder for the ADR-0053 learned advisory signal. Pure and static so
 * it is exactly testable and has NO way to peek at the future: the feature vector at day {@code t} is
 * computed only from bars up to and including {@code t}; the label is the NEXT day's return
 * ({@code t → t+1}). The most recent bar therefore has no label and is excluded — never a forward leak.
 *
 * <p>Features are dimensionless indicators (returns, realized vol, relative volume), so {@code double}
 * is appropriate — this is offline analytics, never a number into positions/PnL/risk (ADR-0016 /
 * invariant 1). The label is a 3-way {@code UP/DOWN/FLAT}: a move counts only if it clears
 * {@code thresholdReturn} (the round-trip-cost floor), else FLAT (no tradeable edge — the model learns
 * "no signal", not a coin-flip on noise).
 */
public final class FeatureBuilder {

    /** Bars of history needed before the first feature row (the longest lookback window). */
    public static final int WARMUP = 20;

    public enum Label { UP, DOWN, FLAT }

    /** One training example: features known AT {@code epochDay}, and the label from the next day. */
    public record FeatureRow(String instrument, long epochDay, double ret1, double ret5, double ret20,
                             double vol20, double volRatio, double forwardReturn, Label label) {
    }

    private FeatureBuilder() {
    }

    /**
     * Builds all valid (feature, label) rows for one instrument's ascending daily bar series.
     * @param thresholdReturn the round-trip-cost floor a next-day move must clear to be UP/DOWN (else
     *                        FLAT). A modelling choice to validate on the harness — not a risk dial.
     */
    public static List<FeatureRow> build(String instrument, List<TrainingBarsStore.Bar> bars, double thresholdReturn) {
        List<FeatureRow> out = new ArrayList<>();
        int n = bars.size();
        if (n < WARMUP + 2) {
            return out; // not enough history for even one labelled row
        }
        double th = Math.abs(thresholdReturn);
        for (int i = WARMUP; i <= n - 2; i++) { // n-2 so a t+1 label exists; WARMUP so windows are full
            double c = bars.get(i).adjClose();
            double ret1 = ret(bars, i, 1);
            double ret5 = ret(bars, i, 5);
            double ret20 = ret(bars, i, 20);
            double vol20 = realizedVol(bars, i, 20);
            double volRatio = relativeVolume(bars, i, 20);
            double fwd = bars.get(i + 1).adjClose() / c - 1.0;
            Label label = fwd > th ? Label.UP : (fwd < -th ? Label.DOWN : Label.FLAT);
            out.add(new FeatureRow(instrument, bars.get(i).epochDay(), ret1, ret5, ret20, vol20, volRatio, fwd, label));
        }
        return out;
    }

    /** Simple return over {@code window} days ending at i: close[i]/close[i-window] − 1. */
    private static double ret(List<TrainingBarsStore.Bar> bars, int i, int window) {
        double past = bars.get(i - window).adjClose();
        return past > 0 ? bars.get(i).adjClose() / past - 1.0 : 0.0;
    }

    /** Sample standard deviation of the last {@code window} daily returns ending at i. */
    private static double realizedVol(List<TrainingBarsStore.Bar> bars, int i, int window) {
        double sum = 0;
        int k = 0;
        for (int j = i - window + 1; j <= i; j++) {
            double prev = bars.get(j - 1).adjClose();
            if (prev > 0) {
                sum += bars.get(j).adjClose() / prev - 1.0;
                k++;
            }
        }
        if (k < 2) {
            return 0.0;
        }
        double mean = sum / k;
        double ss = 0;
        for (int j = i - window + 1; j <= i; j++) {
            double prev = bars.get(j - 1).adjClose();
            if (prev > 0) {
                double r = bars.get(j).adjClose() / prev - 1.0;
                ss += (r - mean) * (r - mean);
            }
        }
        return Math.sqrt(ss / (k - 1));
    }

    /** volume[i] ÷ mean volume over the last {@code window} days; 1.0 when volume is absent (FX). */
    private static double relativeVolume(List<TrainingBarsStore.Bar> bars, int i, int window) {
        double sum = 0;
        int k = 0;
        for (int j = i - window + 1; j <= i; j++) {
            long v = bars.get(j).volume();
            if (v > 0) {
                sum += v;
                k++;
            }
        }
        long cur = bars.get(i).volume();
        if (k == 0 || cur <= 0) {
            return 1.0; // no volume data (FX) → neutral, never a divide-by-zero
        }
        double mean = sum / k;
        return mean > 0 ? cur / mean : 1.0;
    }
}
