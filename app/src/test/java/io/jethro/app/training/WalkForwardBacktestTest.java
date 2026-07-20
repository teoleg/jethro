package io.jethro.app.training;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the ADR-0053 validation gate. The two things that MUST hold: (1) a genuinely learnable,
 * cost-clearing signal ships and beats the baselines; (2) pure noise does NOT ship — the gate's whole
 * job is to refuse a phantom edge. Plus the purge invariant: no training row's label peeks into test.
 */
class WalkForwardBacktestTest {

    private static final WalkForwardBacktest.Config CFG =
            new WalkForwardBacktest.Config(4, 1, 5.0, 20, 300, 0.5, 0.0001);

    /** A learnable rule (next-day sign follows ret1) with moves well above cost → ships, beats baselines. */
    @Test
    void learnableSignalShipsAndBeatsBaselines() {
        List<FeatureBuilder.FeatureRow> rows = new ArrayList<>();
        // Deterministic: label is UP when ret1>0, DOWN when ret1<0, and the forward move (±40 bps) more
        // than clears the 5 bps cost. A momentum baseline on ret5 (here uncorrelated) should NOT beat it.
        long seed = 1234567;
        for (int i = 0; i < 800; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            double ret1 = ((seed >>> 40) % 2 == 0) ? 0.02 : -0.02;
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            double ret5 = ((seed >>> 40) % 2 == 0) ? 0.01 : -0.01; // independent of the label
            double fwd = ret1 > 0 ? 0.004 : -0.004; // +40 bps in the ret1 direction, > 5 bps cost
            FeatureBuilder.Label label = fwd > 0.001 ? FeatureBuilder.Label.UP
                    : fwd < -0.001 ? FeatureBuilder.Label.DOWN : FeatureBuilder.Label.FLAT;
            rows.add(new FeatureBuilder.FeatureRow("SYN", 1000 + i, ret1, ret5, 0.0, 0.01, 1.0, fwd, label));
        }
        WalkForwardBacktest.Result r = WalkForwardBacktest.run(rows, CFG);

        assertTrue(r.sufficient(), r.verdict());
        assertTrue(r.ships(), "a learnable, cost-clearing signal must ship: " + r.verdict());
        assertTrue(r.learned().netReturnBps() > 0, "net edge should be positive");
        assertTrue(r.learned().netReturnBps() > r.coinFlip().netReturnBps(), "must beat coin-flip");
        assertTrue(r.learned().netReturnBps() > r.momentum().netReturnBps(), "must beat momentum");
        assertTrue(r.learned().netReturnBps() > r.meanReversion().netReturnBps(), "must beat mean-reversion");
        assertTrue(r.coinFlip().netReturnBps() < 0, "trading noise pays the cost → negative");
    }

    /** Pure noise: features carry no information about the label → the gate must VETO (no phantom edge). */
    @Test
    void noiseDoesNotShip() {
        List<FeatureBuilder.FeatureRow> rows = new ArrayList<>();
        long seed = 42;
        for (int i = 0; i < 800; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            double ret1 = ((seed >>> 33) & 0xFFFF) / 65535.0 - 0.5;
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            double fwd = (((seed >>> 33) & 0xFFFF) / 65535.0 - 0.5) * 0.02; // ±1% noise, independent
            FeatureBuilder.Label label = fwd > 0.001 ? FeatureBuilder.Label.UP
                    : fwd < -0.001 ? FeatureBuilder.Label.DOWN : FeatureBuilder.Label.FLAT;
            rows.add(new FeatureBuilder.FeatureRow("NZE", 1000 + i, ret1, 0.0, 0.0, 0.0, 1.0, fwd, label));
        }
        WalkForwardBacktest.Result r = WalkForwardBacktest.run(rows, CFG);
        assertTrue(r.sufficient(), r.verdict());
        assertFalse(r.ships(), "noise must not manufacture a shippable edge: " + r.verdict());
    }

    /** Too little history → an honest "insufficient", never an exception or a bogus verdict. */
    @Test
    void tooLittleHistoryIsInsufficient() {
        List<FeatureBuilder.FeatureRow> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            rows.add(new FeatureBuilder.FeatureRow("X", 1000 + i, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, FeatureBuilder.Label.FLAT));
        }
        WalkForwardBacktest.Result r = WalkForwardBacktest.run(rows, CFG);
        assertFalse(r.sufficient());
        assertFalse(r.ships());
    }

    /** The softmax model recovers a clean linear boundary — sanity that fit/predict work. */
    @Test
    void modelLearnsSeparableClasses() {
        double[][] x = new double[300][];
        int[] y = new int[300];
        long seed = 7;
        for (int i = 0; i < 300; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            int cls = (int) ((seed >>> 40) % 3);
            double base = cls == LogisticSignalModel.UP ? 1.0 : cls == LogisticSignalModel.DOWN ? -1.0 : 0.0;
            x[i] = new double[]{base + 0.01 * (((seed >>> 20) & 0xFF) / 255.0 - 0.5), 0.0, 0.0, 0.0, 1.0};
            y[i] = cls;
        }
        LogisticSignalModel m = new LogisticSignalModel(500, 0.5, 0.0);
        m.fit(x, y);
        assertEquals(LogisticSignalModel.UP, m.classify(new double[]{1.0, 0, 0, 0, 1.0}));
        assertEquals(LogisticSignalModel.DOWN, m.classify(new double[]{-1.0, 0, 0, 0, 1.0}));
        assertEquals(LogisticSignalModel.FLAT, m.classify(new double[]{0.0, 0, 0, 0, 1.0}));
    }
}
