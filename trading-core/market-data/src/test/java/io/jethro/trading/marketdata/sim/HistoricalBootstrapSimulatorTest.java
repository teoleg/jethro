package io.jethro.trading.marketdata.sim;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The history-anchored bootstrap engine (ADR-0032): determinism, that an untouched control
 * reproduces the seeded tape, that the resampled path preserves the source return distribution
 * (the whole point — real dispersion, not a parametric guess), and the live-control overlays.
 */
class HistoricalBootstrapSimulatorTest {

    private static final List<String> IDS = List.of("AAPL", "ES");
    private static final long[] STARTS = {190_000_000L, 5_450_000_000L}; // scaled 1e6

    private static HistoricalSnapshot snapshot() {
        // 800 real-ish days from the labelled synthetic generator — enough to measure dispersion.
        return HistoricalSnapshot.synthetic(7, IDS,
                new double[]{190.0, 5450.0}, new double[]{0.28, 0.16},
                new long[]{50_000_000L, 1_500_000L}, 800);
    }

    private static HistoricalBootstrapSimulator sim(long seed, SimControl control) {
        return new HistoricalBootstrapSimulator(seed, snapshot(), IDS, STARTS, 5.0, control);
    }

    @Test
    void sameSeedSameTape() {
        var a = sim(42, new SimControl(1, IDS));
        var b = sim(42, new SimControl(1, IDS));
        for (int t = 0; t < 2_000; t++) {
            a.nextTick();
            b.nextTick();
            for (int i = 0; i < IDS.size(); i++) {
                assertEquals(a.priceScaled(i), b.priceScaled(i), "tick " + t + " instrument " + i);
            }
        }
    }

    @Test
    void bootstrapPreservesTheSourceReturnDispersion() {
        HistoricalSnapshot snap = snapshot();
        double[][] srcReturns = snap.logReturns();
        double srcStd = std(srcReturns[0]); // AAPL

        var sim = sim(11, new SimControl(1, IDS));
        int n = 30_000;
        double[] tape = new double[n];
        double prev = sim.priceScaled(0) / 1_000_000.0;
        for (int t = 0; t < n; t++) {
            sim.nextTick();
            double p = sim.priceScaled(0) / 1_000_000.0;
            tape[t] = Math.log(p / prev);
            prev = p;
        }
        double tapeStd = std(tape);
        // Resampling the real returns must reproduce their dispersion (±25% sampling tolerance).
        assertTrue(Math.abs(tapeStd - srcStd) / srcStd < 0.25,
                "bootstrap std " + tapeStd + " should track source std " + srcStd);
    }

    @Test
    void driftBiasLiftsTheName() {
        var baseline = sim(3, new SimControl(1, IDS));
        var control = new SimControl(1, IDS);
        control.setDriftBias("AAPL", 0.001);
        var driven = sim(3, control);
        for (int t = 0; t < 500; t++) {
            baseline.nextTick();
            driven.nextTick();
        }
        assertTrue(driven.priceScaled(0) > baseline.priceScaled(0),
                "positive drift must lift AAPL above the undriven bootstrap on the same seed");
    }

    @Test
    void reseedResetsToStart() {
        var control = new SimControl(1, IDS);
        var sim = sim(5, control);
        for (int t = 0; t < 400; t++) {
            sim.nextTick();
        }
        control.reseed(123);
        sim.nextTick(); // reseed consumed: prices reset to start, then one step
        long distance = Math.abs(sim.priceScaled(0) - STARTS[0]);
        // Near the start after reset — one daily bootstrap return off it, never the drifted level.
        assertTrue(distance < STARTS[0] / 10, "reseed must snap AAPL back near its seeded start");
    }

    @Test
    void currentDailyVolumeIsPositiveOnceStepped() {
        var sim = sim(9, new SimControl(1, IDS));
        sim.nextTick();
        assertTrue(sim.currentDailyVolume(0) > 0, "a stepped bootstrap exposes the day's real volume");
    }

    @Test
    void rejectsMisalignedInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> new HistoricalBootstrapSimulator(1, snapshot(), IDS, new long[]{1L}, 5.0, new SimControl(1, IDS)));
    }

    private static double std(double[] x) {
        double sum = 0;
        double sumSq = 0;
        for (double v : x) {
            sum += v;
            sumSq += v * v;
        }
        double mean = sum / x.length;
        return Math.sqrt(sumSq / x.length - mean * mean);
    }
}
