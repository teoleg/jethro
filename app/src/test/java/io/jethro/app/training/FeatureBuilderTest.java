package io.jethro.app.training;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exact-value tests for the ADR-0053 point-in-time feature/label builder. The whole point of a pure,
 * static builder is that its arithmetic is checkable by hand — and the one thing that MUST hold is no
 * forward leak: the last bar has no next-day label and is excluded.
 */
class FeatureBuilderTest {

    private static final double THRESHOLD = 0.001; // 10 bps

    /** Flat history then a +1% day then a revert — checks row count, labels, returns, and no-leak. */
    @Test
    void buildsPointInTimeRowsWithoutForwardLeak() {
        // 23 bars (epochDays 0..22): 100 for days 0..20, then 101, then back to 100. Volume 0 (FX-like).
        List<TrainingBarsStore.Bar> bars = new ArrayList<>();
        for (int d = 0; d <= 20; d++) {
            bars.add(new TrainingBarsStore.Bar(d, 100.0, 0));
        }
        bars.add(new TrainingBarsStore.Bar(21, 101.0, 0));
        bars.add(new TrainingBarsStore.Bar(22, 100.0, 0));

        List<FeatureBuilder.FeatureRow> rows = FeatureBuilder.build("TEST", bars, THRESHOLD);

        // WARMUP=20, n=23 → rows at i=20 and i=21 only; i=22 (the last bar) has no label → excluded.
        assertEquals(2, rows.size());
        assertEquals(20, rows.get(0).epochDay());
        assertEquals(21, rows.get(1).epochDay(), "last labelled row is bar n-2 — the final bar is never used");

        FeatureBuilder.FeatureRow r0 = rows.get(0);
        assertEquals(0.0, r0.ret1(), 1e-12);
        assertEquals(0.0, r0.vol20(), 1e-12);
        assertEquals(1.0, r0.volRatio(), 1e-12, "no volume (FX) → neutral relative volume");
        assertEquals(0.01, r0.forwardReturn(), 1e-12); // 101/100 - 1
        assertEquals(FeatureBuilder.Label.UP, r0.label(), "+100 bps clears the 10 bps floor");

        FeatureBuilder.FeatureRow r1 = rows.get(1);
        assertEquals(0.01, r1.ret1(), 1e-12); // 101/100 - 1
        assertEquals(0.01, r1.ret5(), 1e-12); // 101/100 - 1 (bar 16 was still 100)
        assertEquals(0.01, r1.ret20(), 1e-12); // 101/100 - 1 (bar 1 was still 100)
        assertEquals(100.0 / 101.0 - 1.0, r1.forwardReturn(), 1e-12);
        assertEquals(FeatureBuilder.Label.DOWN, r1.label(), "-99 bps clears the floor downward");
    }

    /** A move smaller than the threshold is FLAT, not a coin-flip on noise. */
    @Test
    void subThresholdMoveIsFlat() {
        List<TrainingBarsStore.Bar> bars = new ArrayList<>();
        for (int d = 0; d <= 20; d++) {
            bars.add(new TrainingBarsStore.Bar(d, 100.0, 0));
        }
        bars.add(new TrainingBarsStore.Bar(21, 100.05, 0)); // +5 bps < 10 bps floor
        bars.add(new TrainingBarsStore.Bar(22, 100.0, 0));

        List<FeatureBuilder.FeatureRow> rows = FeatureBuilder.build("TEST", bars, THRESHOLD);
        assertEquals(FeatureBuilder.Label.FLAT, rows.get(0).label());
    }

    /** With volume present, volRatio is current ÷ trailing mean; a spike reads > 1. */
    @Test
    void relativeVolumeReflectsTrailingMean() {
        List<TrainingBarsStore.Bar> bars = new ArrayList<>();
        for (int d = 0; d <= 20; d++) {
            bars.add(new TrainingBarsStore.Bar(d, 100.0, 1_000));
        }
        bars.add(new TrainingBarsStore.Bar(21, 100.0, 3_000)); // 3x the steady 1,000
        bars.add(new TrainingBarsStore.Bar(22, 100.0, 1_000));

        List<FeatureBuilder.FeatureRow> rows = FeatureBuilder.build("TEST", bars, THRESHOLD);
        // Row i=20: current volume 1,000 vs trailing mean 1,000 → 1.0.
        assertEquals(1.0, rows.get(0).volRatio(), 1e-12);
        // Row i=21: current 3,000 vs trailing-20 mean (nineteen 1,000s + one 3,000)/20 = 1,100 → ~2.727.
        assertTrue(rows.get(1).volRatio() > 2.7 && rows.get(1).volRatio() < 2.75,
                "spike day reads well above 1: " + rows.get(1).volRatio());
    }

    /** Not enough history for even one labelled row → empty, never an exception. */
    @Test
    void tooShortIsEmpty() {
        List<TrainingBarsStore.Bar> bars = new ArrayList<>();
        for (int d = 0; d < FeatureBuilder.WARMUP; d++) {
            bars.add(new TrainingBarsStore.Bar(d, 100.0, 0));
        }
        assertTrue(FeatureBuilder.build("TEST", bars, THRESHOLD).isEmpty());
    }
}
