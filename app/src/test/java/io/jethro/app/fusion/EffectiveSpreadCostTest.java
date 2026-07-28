package io.jethro.app.fusion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The ADR-0112 print-implied round trip: Roll's (1984) estimator, and the floor that keeps it a
 * per-name statement only.
 */
class EffectiveSpreadCostTest {

    /** A two-tick bounce: prices alternate between {@code low} and {@code high}. */
    private static List<BigDecimal> bounce(String low, String high, int prints) {
        List<BigDecimal> out = new ArrayList<>(prints);
        for (int i = 0; i < prints; i++) {
            out.add(new BigDecimal(i % 2 == 0 ? low : high));
        }
        return out;
    }

    @Test
    @DisplayName("worked example: a pure 100.00/100.02 bounce reads 2 × the touch, by hand")
    void workedExample() {
        // By hand, on the alternating series p = 100.00, 100.02, 100.00, ... (201 prints, so an EVEN
        // number of steps and the sample mean is exactly zero):
        //   a  = ln(100.02/100.00) = 1.9998000266...e-4        one step, in log-return units
        //   r  = (+a, -a, +a, -a, ...) over 200 steps, mean 0
        //   every adjacent pair contributes r_t * r_{t-1} = -a^2, so cov = -a^2
        //   s  = 2 * sqrt(a^2) = 2a = 3.99960005...e-4 -> x 1e4 = 3.9996 bps
        // The TRUE full spread here is 0.02 on a mid of 100.01 = 1.9998 bps, so a perfectly
        // alternating tape reads DOUBLE it. That is Roll's known behaviour when signs alternate more
        // than i.i.d. — the estimator over-states, which is the safe direction for a cost gate.
        double a = Math.log(100.02 / 100.00);
        double expected = 2.0 * a * 10_000.0;
        assertThat(expected).isCloseTo(3.9996, within(1e-3));

        var estimate = EffectiveSpreadCost.roundTripBps(bounce("100.00", "100.02", 201));
        assertThat(estimate).isPresent();
        assertThat(estimate.getAsDouble()).isCloseTo(expected, within(1e-9));

        // An ODD number of steps leaves the mean at a/199 rather than 0, and demeaning then reads
        // cov = -a^2(1 - 1/199^2) — the same estimate, shy by one part in 2*199^2. Pinned so the
        // small-sample behaviour of the demeaning is a documented property, not a surprise.
        double odd = EffectiveSpreadCost.roundTripBps(bounce("100.00", "100.02", 200)).orElseThrow();
        assertThat(odd).isCloseTo(expected * Math.sqrt(1.0 - 1.0 / (199.0 * 199.0)), within(1e-12));
    }

    @Test
    @DisplayName("a wider touch reads proportionally wider — the estimate scales with the spread")
    void scalesWithTheSpread() {
        double narrow = EffectiveSpreadCost.roundTripBps(bounce("100.00", "100.02", 200)).orElseThrow();
        double wide = EffectiveSpreadCost.roundTripBps(bounce("100.00", "100.10", 200)).orElseThrow();
        assertThat(wide).isGreaterThan(narrow);
        // 5x the tick on the same level is 5x the spread, to the accuracy of the log approximation.
        assertThat(wide / narrow).isCloseTo(5.0, within(0.01));
    }

    @Test
    @DisplayName("a tape that moves in RUNS has positive autocovariance — NO measurement, never a flipped sign")
    void trendingTapeDeclinesToMeasure() {
        // Three ticks up, three ticks down, repeating: the returns are positively autocorrelated
        // (four same-sign adjacent pairs for every two opposite-sign ones), which is exactly what a
        // trending tape looks like and exactly where Roll's model does not hold. The honest answer is
        // "no measurement" — NOT a cost taken from |cov|, which is the classic misuse of this
        // estimator and would manufacture a hurdle out of momentum.
        List<BigDecimal> runs = new ArrayList<>();
        int tick = 0;
        for (int i = 0; i < 240; i++) {
            tick += (i / 3) % 2 == 0 ? 1 : -1;
            runs.add(new BigDecimal("100.00").add(new BigDecimal(tick).movePointLeft(2)));
        }
        assertThat(EffectiveSpreadCost.roundTripBps(runs)).isEmpty();
    }

    @Test
    @DisplayName("too few distinct-price steps is no measurement, not a small one")
    void belowMinimumSampleIsEmpty() {
        assertThat(EffectiveSpreadCost.roundTripBps(bounce("100.00", "100.02", 10))).isEmpty();
        assertThat(EffectiveSpreadCost.roundTripBps(List.of())).isEmpty();
        assertThat(EffectiveSpreadCost.roundTripBps(null)).isEmpty();
    }

    @Test
    @DisplayName("repeated conflated prints are collapsed, not counted as zero-return steps")
    void repeatedPrintsAreCollapsed() {
        List<BigDecimal> plain = bounce("100.00", "100.02", 200);
        // The same tape, but every print re-published three times by the ~1Hz conflation.
        List<BigDecimal> conflated = new ArrayList<>();
        for (BigDecimal price : plain) {
            conflated.add(price);
            conflated.add(price);
            conflated.add(price);
        }
        double a = EffectiveSpreadCost.roundTripBps(plain).orElseThrow();
        double b = EffectiveSpreadCost.roundTripBps(conflated).orElseThrow();
        // Counting the repeats as zero returns would drag the autocovariance toward zero and UNDERSTATE
        // the spread — the unsafe direction for a gate. Collapsing them makes the two tapes identical.
        assertThat(b).isCloseTo(a, within(1e-9));
    }

    @Test
    @DisplayName("a hole in the series truncates it rather than pairing across the gap")
    void holeTruncates() {
        List<BigDecimal> withHole = new ArrayList<>();
        withHole.addAll(bounce("100.00", "100.02", 200)); // old, before the hole
        withHole.add(null);                               // an unusable price
        withHole.addAll(bounce("100.00", "100.02", 10));  // too few steps after it
        // Only the contiguous tail after the hole is usable, and it is below the minimum sample.
        assertThat(EffectiveSpreadCost.roundTripBps(withHole)).isEmpty();
    }

    // ---- the fallback: per-name only, floored at what the desk has actually paid ----

    @Test
    @DisplayName("an unmeasured name takes its own estimate; a measured one is never overridden")
    void estimateFillsOnlyTheGap() {
        Map<String, Double> measured = new LinkedHashMap<>();
        measured.put("AAPL", 0.90);
        measured.put("GOOGL", 20.10);
        double blend = 1.33;
        double floor = EffectiveSpreadCost.cheapestMeasured(blend, measured);
        assertThat(floor).isEqualTo(0.90);

        Map<String, Double> estimated = new LinkedHashMap<>();
        estimated.put("AAPL", 5.00);   // ignored: a fill beats an inference
        estimated.put("GOOGL", 0.01);  // ignored for the same reason
        estimated.put("EURUSD", 1.05); // charged: no measurement of its own

        var out = EffectiveSpreadCost.withEffectiveSpreadFallback(measured, floor, estimated);
        assertThat(out).containsEntry("AAPL", 0.90).containsEntry("GOOGL", 20.10)
                .containsEntry("EURUSD", 1.05);
    }

    @Test
    @DisplayName("the estimate is floored at the cheapest round trip the desk has actually paid")
    void neverBelowTheCheapestMeasured() {
        Map<String, Double> measured = Map.of("ES", 0.43, "AAPL", 0.90);
        double floor = EffectiveSpreadCost.cheapestMeasured(1.33, measured);
        assertThat(floor).isEqualTo(0.43);

        var out = EffectiveSpreadCost.withEffectiveSpreadFallback(measured, floor,
                Map.of("NQ", 0.01)); // an implausibly cheap inference
        assertThat(out).containsEntry("NQ", 0.43);
    }

    @Test
    @DisplayName("the desk-wide minimum is unchanged, so the desk-wide verdict cannot move")
    void deskWideMinimumIsInvariant() {
        Map<String, Double> measured = Map.of("ES", 0.43, "AAPL", 0.90, "GOOGL", 20.10);
        double blend = 1.33;
        double before = EffectiveSpreadCost.cheapestMeasured(blend, measured);

        var out = EffectiveSpreadCost.withEffectiveSpreadFallback(measured, before,
                Map.of("NQ", 0.01, "EURUSD", 0.50, "TSLA", 9.90));
        double after = EffectiveSpreadCost.cheapestMeasured(blend, out);

        assertThat(after).isEqualTo(before);
        assertThat(out).hasSize(6); // three measured + three newly priced
    }

    @Test
    @DisplayName("a name charged the blend today can only get CHEAPER or stay put, never dearer")
    void perNameHurdleNeverRisesAboveTheBlend() {
        Map<String, Double> measured = Map.of("ES", 0.43);
        double blend = 1.33;
        double floor = EffectiveSpreadCost.cheapestMeasured(blend, measured);
        var out = EffectiveSpreadCost.withEffectiveSpreadFallback(measured, floor,
                Map.of("CHEAP", 0.60, "DEAR", 8.00));
        // Cheaper than the blend: the name becomes tradable on its own evidence.
        assertThat(out.get("CHEAP")).isEqualTo(0.60);
        // Dearer than the blend: the name is charged MORE than it is today, which can only remove a
        // trade. Both directions are the name's own measurement, which is the point.
        assertThat(out.get("DEAR")).isEqualTo(8.00);
    }

    @Test
    @DisplayName("no usable floor, no change — an absent measurement never becomes a number")
    void degenerateInputsLeaveTheMapAlone() {
        Map<String, Double> measured = Map.of("ES", 0.43);
        assertThat(EffectiveSpreadCost.withEffectiveSpreadFallback(measured, Double.NaN,
                Map.of("NQ", 0.60))).isEqualTo(measured);
        assertThat(EffectiveSpreadCost.withEffectiveSpreadFallback(measured, 0.0,
                Map.of("NQ", 0.60))).isEqualTo(measured);
        assertThat(EffectiveSpreadCost.withEffectiveSpreadFallback(measured, 0.43, null))
                .isEqualTo(measured);
        assertThat(EffectiveSpreadCost.withEffectiveSpreadFallback(measured, 0.43,
                Map.of("NQ", Double.NaN))).isEqualTo(measured);
    }

    @Test
    @DisplayName("the closed loop this opens: an unmeasured name held reduce-only by the blend")
    void unfreezesANameTheBlendWouldHoldReduceOnly() {
        // The desk's live shape at the time of ADR-0112: one source clears at the selected rung, and
        // the hurdle it can afford is avgReturn - t*SE. A name charged the blend fails that test; the
        // same name charged its own print-implied spread passes it.
        // resolved 5641 over 500 cohorts, mean +1.9140 bps, SE = 9.0158/sqrt(500) = 0.4032 bps.
        var stats = new io.jethro.app.signal.SignalScoring.Stats(
                "reversion", 5641, 0, 0, 0, 0, 0.0, 1.913961062076329, 15.92925099597445,
                500, 9.015789255028244, 225);
        assertThat(stats.stdErrorBps()).isCloseTo(0.4031983529011068, within(1e-12));
        var params = new EdgeGate.Params(30, 2.0);
        double blend = 1.3265745564111149;
        Map<String, Double> measured = Map.of("ES", 0.43014468085106383, "AAPL", 0.8974084006462035);

        var before = EdgeGate.evaluate(List.of(stats), blend, measured, params);
        assertThat(before.mayIncrease()).isTrue();          // the desk-wide verdict is open
        assertThat(before.mayIncrease("AAPL")).isTrue();    // a cheap MEASURED name may open
        assertThat(before.mayIncrease("NQ")).isFalse();     // an unmeasured one is charged the blend

        double floor = EffectiveSpreadCost.cheapestMeasured(blend, measured);
        var withPrints = EffectiveSpreadCost.withEffectiveSpreadFallback(measured, floor,
                Map.of("NQ", 0.75)); // NQ's own tape, cheaper than the blend
        var after = EdgeGate.evaluate(List.of(stats), blend, withPrints, params);

        assertThat(after.mayIncrease("NQ")).isTrue();       // now it may open, on its own evidence
        assertThat(after.mayIncrease()).isEqualTo(before.mayIncrease());
        assertThat(after.roundTripCostBps()).isEqualTo(before.roundTripCostBps()); // verdict unmoved
    }
}
