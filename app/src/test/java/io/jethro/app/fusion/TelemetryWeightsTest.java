package io.jethro.app.fusion;

import io.jethro.app.signal.SignalScoring;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0055 telemetry-driven weights: evidence in (per-source hit-rate + decisive sample), weight out,
 * shrunk toward equal by credibility and bounded. Worked examples verified by hand (delta on the
 * dimensionless ratios; the money boundary is downstream in scaled decimals).
 */
class TelemetryWeightsTest {

    private static final TelemetryWeights.Params K20 = new TelemetryWeights.Params(20, 0.25, 3.0);

    private static SignalScoring.Stats stat(String source, double hitRate, long wins, long losses) {
        return new SignalScoring.Stats(source, wins + losses, wins, losses, 0, 0, hitRate, 0.0);
    }

    @Test
    void coldStartWeightsEveryoneEqually() {
        var w = TelemetryWeights.compute(List.of(
                stat("momentum", 0.0, 0, 0),
                stat("hypothesis", 0.0, 0, 0),
                stat("social", 0.0, 0, 0)), K20);
        assertEquals(1.0, w.get("momentum"), 1e-9);
        assertEquals(1.0, w.get("hypothesis"), 1e-9);
        assertEquals(1.0, w.get("social"), 1e-9);
    }

    @Test
    void noMeasuredEdgeAnywhereFallsBackToEqual() {
        // a coin-flip and a below-coin-flip source → advantage 0 everywhere → honest equal weighting
        var w = TelemetryWeights.compute(List.of(
                stat("momentum", 0.50, 40, 40),
                stat("social", 0.40, 30, 45)), K20);
        assertEquals(1.0, w.get("momentum"), 1e-9);
        assertEquals(1.0, w.get("social"), 1e-9);
    }

    @Test
    void skilledThickSourceUpDecayedThickSourceFloored() {
        var p = new TelemetryWeights.Params(10, 0.25, 3.0);
        // A: hitRate 0.75, decisive 40 → adv 0.5, c 0.8 ; B: hitRate 0.40, decisive 50 → adv 0, c 0.8333
        // pool 0.25 → shrunkA 0.45, shrunkB 0.0416667, mean 0.2458333 → wA 1.830508, wB 0.1695 → floor 0.25
        var w = TelemetryWeights.compute(List.of(
                stat("A", 0.75, 30, 10),
                stat("B", 0.40, 20, 30)), p);
        assertEquals(1.8305085, w.get("A"), 1e-5);
        assertEquals(0.25, w.get("B"), 1e-9);
        assertTrue(w.get("A") > w.get("B"));
    }

    @Test
    void thinStrongSourceIsShrunkNotAllowedToDominate() {
        // A: perfect but only 3 decisive; B: modest edge over 200 decisive
        var w = TelemetryWeights.compute(List.of(
                stat("A", 1.0, 3, 0),
                stat("B", 0.55, 110, 90)), K20);
        assertTrue(w.get("A") < 2.0, "a 3-sample perfect run must not dominate the combine");
        assertTrue(w.get("A") > w.get("B"), "but it still ranks above a thick-but-modest source");
    }

    @Test
    void weightsAreBoundedByMinAndMaxParams() {
        var tight = new TelemetryWeights.Params(10, 0.5, 1.5);
        var w = TelemetryWeights.compute(List.of(
                stat("A", 0.75, 30, 10),   // raw ≈ 1.83 → clamp to max 1.5
                stat("B", 0.40, 20, 30)), tight); // raw ≈ 0.17 → clamp to min 0.5
        assertEquals(1.5, w.get("A"), 1e-9);
        assertEquals(0.5, w.get("B"), 1e-9);
    }

    @Test
    void singleSourceGetsTheNeutralWeight() {
        var w = TelemetryWeights.compute(List.of(stat("A", 0.9, 90, 10)), K20);
        assertEquals(1.0, w.get("A"), 1e-9);
    }

    @Test
    void emptyTelemetryYieldsNoWeights() {
        assertTrue(TelemetryWeights.compute(List.of(), K20).isEmpty());
    }

    @Test
    void sourceWithoutTelemetryTakesTheNeutralDefault() {
        // the learned signal has its own walk-forward gate, no signal-telemetry entry → neutral 1.0
        var fw = FusionWeights.fromTelemetry(List.of(stat("momentum", 0.75, 30, 10)), K20);
        assertEquals(1.0, fw.weightFor("learned"), 1e-9);
    }
}
