package io.jethro.app.fusion;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0094 — the no-trade region around the aim. Exact-decimal assertions throughout (invariant 1): the
 * worked examples below are the ones in the ADR, computed by hand and asserted to the quantity scale the
 * order and fill records use.
 *
 * <p><b>Worked example (the live AAPL plan this was diagnosed from).</b> forecast −9.64, target
 * −142.319300, held −7, derived rate a = 0.032784 (30 s cycle over a 900 s horizon), buffer 0.10:
 * <pre>
 *   averagePosition = 142.319300 x 10 / 9.64 = 147.634128
 *   band            = 0.10 x 147.634128      =  14.763413
 *   aim (first)     = −7 + 0.032784 x (−142.319300 − (−7)) = −7 − 4.436294… = −11.436294
 *   gap (ADR-0103)  = −142.319300 − (−7)     = −135.319300
 *   |gap| = 135.319300 > 14.763413           ⇒  take the step, −4.436294
 * </pre>
 * ADR-0094 tested {@code aim − held = −4.436294} here and suppressed the order. That gap is the
 * ADR-0080 lag, not the distance from the optimum the width is derived about — a factor of the
 * adjustment rate smaller — so the test could only be passed by drift, never by a view. ADR-0103 tests
 * the target gap: the band now suppresses a target that WOBBLES near the position (the round trip that
 * actually costs money) instead of suppressing the accumulation toward it.
 */
class PositionBufferTest {

    /** 30 s cycle against the 900 s horizon the live evidence selected — the shipped derived rate. */
    private static final double RATE = TargetPlanner.adjustmentRateFor(30, 900);

    private static FusionPlanner.Target target(String instrument, double forecast, String targetQty,
                                               String currentQty) {
        return new FusionPlanner.Target(instrument, forecast, 2, 1.0, new BigDecimal("189.714100"),
                new BigDecimal(targetQty), new BigDecimal(currentQty), BigDecimal.ZERO, List.of());
    }

    @Test
    void averagePositionIsTheTargetAtATypicalForecast() {
        PositionBuffer buffer = new PositionBuffer(0.10);
        // |target| x TARGET_ABS / |forecast| = 142.319300 x 10 / 9.64 = 147.634129…, x 0.10
        assertThat(buffer.band(new BigDecimal("-142.319300"), -9.64, BigDecimal.ZERO))
                .isEqualByComparingTo(new BigDecimal("14.763413"));
        // Scale-free in the forecast: half the forecast is half the target, so the SAME average position.
        assertThat(buffer.band(new BigDecimal("-71.159650"), -4.82, BigDecimal.ZERO))
                .isEqualByComparingTo(new BigDecimal("14.763413"));
    }

    @Test
    void noViewAndNoTargetFallsBackToTheHeldPosition() {
        PositionBuffer buffer = new PositionBuffer(0.10);
        assertThat(buffer.band(BigDecimal.ZERO, 0.0, new BigDecimal("-40")))
                .isEqualByComparingTo(new BigDecimal("4.000000"));
    }

    // ---------------------------------------------------------------------------------------------
    // ADR-0103 — the region is around the TARGET (the position the width is derived about), and the
    // aim step says how far into it to move. The width the desk MEASURES (ADR-0101, 0.24–1.00 average
    // positions live) binds there; Carver's 0.10, which ADR-0094 was written against, did not.
    // ---------------------------------------------------------------------------------------------

    @Test
    void aPositionFarFromItsTargetMovesAtExactlyTheRateLimitedStep() {
        PositionBuffer buffer = new PositionBuffer(0.10);
        var result = buffer.apply(List.of(target("AAPL", -9.64, "-142.319300", "-7")), null, RATE);
        // aim = −7 + a(−142.3193 + 7) = −11.436294; a = 1 − e^(−30/900) = 0.0327839…
        // gap = −142.319300 − (−7) = −135.319300, |gap| > band 14.763413 ⇒ outside the region, so the
        // whole ADR-0080 step trades: −11.436294 − (−7) = −4.436294. At Carver's 0.10 this name is a
        // long way from its optimum and rebalancing plainly pays; ADR-0094 suppressed it because it
        // was testing the aim gap, which is ~3% of the distance the width is about.
        assertThat(result.aims().get("AAPL")).isEqualByComparingTo(new BigDecimal("-11.436294"));
        assertThat(result.targets().get(0).deltaQty()).isEqualByComparingTo(new BigDecimal("-4.436294"));
        assertThat(result.insideBuffer()).isZero();
        assertThat(result.traded()).isEqualTo(1);
        // Outside the region the desk moves at exactly the unbuffered ADR-0080 rate — the buffer
        // decides WHETHER to trade, never how fast.
        assertThat(TargetPlanner.orderDelta(new BigDecimal("-142.319300"), new BigDecimal("-7"),
                0.5, RATE)).isEqualByComparingTo(new BigDecimal("-4.436294"));
    }

    @Test
    void aPositionInsideItsOwnMeasuredBandTradesNothing() {
        // The live GOOG plan this was diagnosed from, at its ADR-0101 measured width. Round trip
        // 1.9714 bps against the passing source's 7.9748 bps ⇒ width 2C/μ = 0.4944; the desk holds −8
        // and the forecast has flipped to +2.00, target +28.092000:
        //   averagePosition = 28.092000 x 10 / 2.00 = 140.460000
        //   band            = 140.460000 x 0.4944   =  69.443424
        //   gap             = 28.092000 − (−8)      =  36.092000  ≤ band ⇒ NO ORDER
        // The old rule read the ADR-0102 sign clamp (aim 0) as an exit and bought all 8 back at
        // market. It is a wobble inside the band, and the desk now sits through it.
        assertThat(PositionBuffer.bufferedDelta(BigDecimal.ZERO, new BigDecimal("-8"),
                new BigDecimal("69.443424"), new BigDecimal("28.092000")))
                .isEqualByComparingTo("0");
    }

    @Test
    void theStepIsCappedAtTheNearEdgeAndNeverCarriesThePositionPastIt() {
        // Intent already at −40 against a −7 holding, target −142.319300, band 14.763413. The step
        // −33 lands at −40, still 102.319300 short of the target ⇒ taken whole.
        assertThat(PositionBuffer.bufferedDelta(new BigDecimal("-40"), new BigDecimal("-7"),
                new BigDecimal("14.763413"), new BigDecimal("-142.319300")))
                .isEqualByComparingTo(new BigDecimal("-33.000000"));
        // Intent at −140: the step −133 would carry the position past the near edge, so it is capped
        // at gap − band·sgn = −(135.319300 − 14.763413) = −120.555887, landing at −7 − 120.555887 =
        // −127.555887 = target + band exactly — one full buffer inside the target, as before.
        assertThat(PositionBuffer.bufferedDelta(new BigDecimal("-140"), new BigDecimal("-7"),
                new BigDecimal("14.763413"), new BigDecimal("-142.319300")))
                .isEqualByComparingTo(new BigDecimal("-120.555887"));
        assertThat(new BigDecimal("-7").add(new BigDecimal("-120.555887")))
                .isEqualByComparingTo(new BigDecimal("-142.319300").add(new BigDecimal("14.763413")));
    }

    @Test
    void anIntentThatDoesNotPointAtTheOptimumTradesNothing() {
        // Held −7 against a target of −142.319300 (gap −135.319300, outside the band) but an intent of
        // +5: the desk would be buying while its own optimum is further short. Nothing to do.
        assertThat(PositionBuffer.bufferedDelta(new BigDecimal("5"), new BigDecimal("-7"),
                new BigDecimal("14.763413"), new BigDecimal("-142.319300")))
                .isEqualByComparingTo("0");
    }

    @Test
    void aFlatTargetIsAnExitAndIsNeverBuffered() {
        PositionBuffer buffer = new PositionBuffer(0.10);
        // A risk cut / orphan unwind / silenced source plans the name flat: the whole position goes,
        // this cycle, exactly as ADR-0090 works it — no buffer, no partial adjustment.
        var result = buffer.apply(List.of(target("AAPL", 0.0, "0", "-47")), null, RATE);
        assertThat(result.aims().get("AAPL")).isEqualByComparingTo("0");
        assertThat(result.targets().get(0).deltaQty()).isEqualByComparingTo(new BigDecimal("47.000000"));
    }

    @Test
    void aFlatTargetClearsIntentSoTheCutIsNotUndoneNextCycle() {
        PositionBuffer buffer = new PositionBuffer(0.10);
        buffer.apply(List.of(target("AAPL", -9.64, "-142.319300", "-7")), null, RATE);
        buffer.apply(List.of(target("AAPL", 0.0, "0", "-11")), null, RATE); // stopped out
        // Re-armed with a fresh view, the aim restarts from the (now flat) book — not from the old
        // intent — and ADR-0103 lets the rebuild start on that very cycle: aim = 0 + a(−142.319300) =
        // −4.665782, gap = −142.319300 outside the band, so the step trades whole. Under the old rule
        // the desk waited for the aim to accumulate a full band (~11 cycles) before its first share,
        // and the mean-reverting source changed side long before that.
        var again = buffer.apply(List.of(target("AAPL", -9.64, "-142.319300", "0")), null, RATE);
        assertThat(again.aims().get("AAPL")).isEqualByComparingTo(new BigDecimal("-4.665782"));
        assertThat(again.targets().get(0).deltaQty()).isEqualByComparingTo(new BigDecimal("-4.665782"));
    }

    @Test
    void aClosedGateClampsToReduceOnlyAndStopsIntentRunningAway() {
        PositionBuffer buffer = new PositionBuffer(0.10);
        EdgeGate.Decision closed = new EdgeGate.Decision(false, 1.0, "no measured edge", List.of(),
                java.util.Map.of(), new EdgeGate.Params(2, 2.0), 900L);
        var result = buffer.apply(List.of(target("AAPL", -9.64, "-142.319300", "0")), closed, RATE);
        assertThat(result.targets().get(0).deltaQty()).isEqualByComparingTo("0");
        // Intent is re-seeded to where the desk actually is, so ten cycles of a shut gate cannot
        // accumulate into one large order the moment it reopens.
        assertThat(result.aims().get("AAPL")).isEqualByComparingTo("0");
    }

    @Test
    void aClosedGateStillLetsThePositionBeCut() {
        PositionBuffer buffer = new PositionBuffer(0.10);
        EdgeGate.Decision closed = new EdgeGate.Decision(false, 1.0, "no measured edge", List.of(),
                java.util.Map.of(), new EdgeGate.Params(2, 2.0), 900L);
        // Intent settled near −19.66 while the book is short 120: the buffered move BUYS BACK, which
        // reduces |position|, so the closed gate must let it through rather than trap the desk short.
        buffer.apply(List.of(target("AAPL", -1.0, "-14.766000", "-20")), closed, RATE);
        var result = buffer.apply(List.of(target("AAPL", -1.0, "-14.766000", "-120")), closed, RATE);
        // The EWMA step is −20 + a(−14.766000 + 20) = −19.828409, which overshoots the target it is
        // decaying toward; ADR-0102 holds the intent at −14.766000. gap = −14.766000 + 120 =
        // +105.234000, band 14.766000 ⇒ +90.468000, and reduce-only leaves it whole because it never
        // crosses through flat. The clamp BUYS BACK more of the short, never less: strictly one-way.
        assertThat(result.targets().get(0).deltaQty()).isEqualByComparingTo(new BigDecimal("90.468000"));
    }

    // ---------------------------------------------------------------------------------------------
    // ADR-0102 — the aim is a convex combination of PAST targets, so it can outgrow or invert the
    // CURRENT one. Clamp it into the closed interval between flat and this cycle's target.
    // ---------------------------------------------------------------------------------------------

    @Test
    void anIntentThatOutgrewItsTargetIsHeldAtTheTarget() {
        // The live EURUSD plan this was diagnosed from: aim −19268.293125 against a target that had
        // shrunk to −13006.790342. The desk intended 6261.502783 units more short than its own
        // forecast asked for — at a 1.084448 mark, $6,789.15 of notional nothing had planned.
        assertThat(PositionBuffer.withinTarget(new BigDecimal("-19268.293125"),
                new BigDecimal("-13006.790342")))
                .isEqualByComparingTo(new BigDecimal("-13006.790342"));
        // Under the target it is the desk's own path and is untouched.
        assertThat(PositionBuffer.withinTarget(new BigDecimal("-31.328741"),
                new BigDecimal("-104.157858")))
                .isEqualByComparingTo(new BigDecimal("-31.328741"));
        // At the target exactly, likewise — the bound is closed.
        assertThat(PositionBuffer.withinTarget(new BigDecimal("200.000000"), new BigDecimal("200")))
                .isEqualByComparingTo(new BigDecimal("200.000000"));
    }

    @Test
    void anIntentOnTheWrongSideOfTheViewIsHeldAtFlat() {
        // The live JNJ plan: intent +8.043404 while the forecast (−12.64) targeted −219.420787. The
        // desk was buying toward a long in the name its own evidence said to be short.
        assertThat(PositionBuffer.withinTarget(new BigDecimal("8.043404"),
                new BigDecimal("-219.420787")))
                .isEqualByComparingTo("0");
        // And the mirror case.
        assertThat(PositionBuffer.withinTarget(new BigDecimal("-11494.134880"),
                new BigDecimal("17285.020487")))
                .isEqualByComparingTo("0");
    }

    @Test
    void theClampIsStrictlyOneWayOnEveryBranch() {
        // |aim'| <= |aim| and sgn(aim') in {0, sgn(target)} — the two properties that make this
        // incapable of opening, enlarging or side-flipping a position.
        for (String a : List.of("-19268.293125", "-31.328741", "8.043404", "0", "200.000000")) {
            for (String t : List.of("-219.420787", "17285.020487", "0", "-13006.790342")) {
                BigDecimal aim = new BigDecimal(a);
                BigDecimal tgt = new BigDecimal(t);
                BigDecimal out = PositionBuffer.withinTarget(aim, tgt);
                assertThat(out.abs()).isLessThanOrEqualTo(aim.abs());
                if (out.signum() != 0 && tgt.signum() != 0) {
                    assertThat(out.signum()).isEqualTo(tgt.signum());
                }
            }
        }
    }

    @Test
    void aFlatTargetKeepsItsExitSemanticsExactly() {
        // The flat-target branch returns before the clamp, so the ADR-0086 cut / ADR-0065 unwind and
        // the deterministic floor above them are untouched: a flat target still snaps intent to zero.
        PositionBuffer buffer = new PositionBuffer(0.10);
        var exit = buffer.apply(List.of(target("AAPL", 0.0, "0", "-47")), null, RATE);
        assertThat(exit.aims().get("AAPL")).isEqualByComparingTo("0");
        assertThat(exit.targets().get(0).deltaQty()).isEqualByComparingTo(new BigDecimal("47.000000"));
    }

    @Test
    void anInvertedIntentIsCutOnceAndThenRebuildsThroughTheBuffer() {
        PositionBuffer buffer = new PositionBuffer(0.10);
        // Long 104 while the target is −219.420787: the first cycle clamps intent to flat and sells
        // the whole holding (an aim of zero is not buffered), which is the risk-REDUCING half of the
        // move — it stops at flat and does not build the short at full speed.
        var cut = buffer.apply(List.of(target("JNJ", -12.64, "-219.420787", "104")), null, RATE);
        assertThat(cut.aims().get("JNJ")).isEqualByComparingTo("0");
        assertThat(cut.targets().get(0).deltaQty()).isEqualByComparingTo(new BigDecimal("-104.000000"));
        // Next cycle the path restarts from flat: aim = 0 + a(−219.420787) = −7.193469. Under ADR-0103
        // the gap that decides is −219.420787 − 0, far outside the band of
        // 219.420787 x 10 / 12.64 x 0.10 = 17.359240, so the short is rebuilt from this very cycle at
        // the derived rate — not round-tripped, and not stalled for the ~11 cycles the aim would have
        // needed to accumulate a whole band under ADR-0094.
        var rebuild = buffer.apply(List.of(target("JNJ", -12.64, "-219.420787", "0")), null, RATE);
        assertThat(rebuild.aims().get("JNJ")).isEqualByComparingTo(new BigDecimal("-7.193469"));
        assertThat(rebuild.targets().get(0).deltaQty()).isEqualByComparingTo(new BigDecimal("-7.193469"));
    }

    @Test
    void aNameThatLeavesTheBookLeavesNoIntentBehind() {
        PositionBuffer buffer = new PositionBuffer(0.10);
        buffer.apply(List.of(target("AAPL", -9.64, "-142.319300", "-7")), null, RATE);
        var next = buffer.apply(List.of(target("MSFT", 8.0, "100", "0")), null, RATE);
        assertThat(next.aims()).containsOnlyKeys("MSFT");
    }

    // ---------------------------------------------------------------------------------------------
    // ADR-0101 — the buffer WIDTH is the desk's own measured cost-to-edge ratio, floored at Carver's
    // convention. width = max(0.10, min(1, 2C/mu)); C = the name's own measured round trip in bps
    // (desk blend when it has never filled), mu = the gross expectancy of the best-evidenced PASSING
    // source. Every figure below is computed by hand in the assertion's own comment.
    // ---------------------------------------------------------------------------------------------

    /** A gate with one passing source at {@code edgeBps} and the given per-name measured costs. */
    private static EdgeGate.Decision gateAt(double edgeBps, double blendBps,
                                            java.util.Map<String, Double> perName) {
        var passing = new EdgeGate.SourceEdge("reversion", 500L, 65L, edgeBps, 0.95,
                edgeBps - blendBps, 9.3, 1e-9, true);
        var failing = new EdgeGate.SourceEdge("trend", 500L, 33L, -4.688401, 1.07,
                -4.688401 - blendBps, -4.37, 0.9999, false);
        return new EdgeGate.Decision(true, blendBps, "measured edge clears", List.of(passing, failing),
                perName, new EdgeGate.Params(2, 2.0), 900L);
    }

    @Test
    void theWidthIsTwiceTheNamesOwnMeasuredCostOverTheMeasuredEdge() {
        PositionBuffer buffer = new PositionBuffer(0.10);
        var gate = gateAt(8.0, 5.0, java.util.Map.of("AAPL", 2.0));
        // 2C/mu = 2 x 2.0 / 8.0 = 0.50 exactly — five times Carver's convention, so the measured
        // width governs.
        assertThat(buffer.widthFor("AAPL", gate, PositionBuffer.passingEdgeBps(gate))).isEqualTo(0.50);
        // averagePosition = |200| x 10 / 16 = 125 exactly; band = 125 x 0.50 = 62.500000.
        assertThat(buffer.band(new BigDecimal("200"), 16.0, BigDecimal.ZERO, 0.50))
                .isEqualByComparingTo(new BigDecimal("62.500000"));
        // Against Carver's 0.10 the same name would have banded at 125 x 0.10 = 12.500000.
        assertThat(buffer.band(new BigDecimal("200"), 16.0, BigDecimal.ZERO))
                .isEqualByComparingTo(new BigDecimal("12.500000"));
    }

    @Test
    void aMoveTheConventionWouldHaveTradedIsInsideTheMeasuredBuffer() {
        // ADR-0103 moved the region back onto the target, so the measured WIDTH now bites where a
        // width is supposed to bite: on a position already NEAR its target, where the remaining move
        // is small and the round trip is not worth paying for. Held 160 against a target of 200:
        //   averagePosition = 200 x 10 / 16 = 125     measured band = 125 x 0.50 = 62.500000
        //   gap             = 200 - 160     =  40     40 <= 62.500000  =>  NO ORDER
        PositionBuffer buffer = new PositionBuffer(0.10);
        var gate = gateAt(8.0, 5.0, java.util.Map.of("AAPL", 2.0));
        var measured = buffer.apply(List.of(target("AAPL", 16.0, "200", "160")), gate, RATE);
        assertThat(measured.targets().get(0).deltaQty()).isEqualByComparingTo("0");
        assertThat(measured.insideBuffer()).isEqualTo(1);

        // The same cycle with no measurement: Carver's 0.10 bands at 12.500000, the gap of 40 is
        // outside it, and the desk pays for the ADR-0080 step
        //   aim = 160 + a(200 - 160) = 161.311356,  a = 1 - e^(-30/900) = 0.0327838995…
        // which is 1.311356 of turnover this desk's own cost-to-edge ratio says to skip.
        PositionBuffer unmeasured = new PositionBuffer(0.10);
        var conventional = unmeasured.apply(List.of(target("AAPL", 16.0, "200", "160")), null, RATE);
        assertThat(conventional.aims().get("AAPL")).isEqualByComparingTo(new BigDecimal("161.311356"));
        assertThat(conventional.targets().get(0).deltaQty())
                .isEqualByComparingTo(new BigDecimal("1.311356"));
    }

    @Test
    void theMeasuredWidthStillDoesNotStallTheApproachToATarget() {
        // The failure ADR-0103 fixes, stated as a test: from a FLAT book the same measured 62.500000
        // band must not suppress the approach — the gap is 200, far outside it, so the desk moves at
        // the derived rate from the first cycle. Under ADR-0094 this name traded nothing until the
        // aim itself had accumulated past 62.500000, which the live 900 s mean-reverting source never
        // gave it before changing side.
        PositionBuffer buffer = new PositionBuffer(0.10);
        var gate = gateAt(8.0, 5.0, java.util.Map.of("AAPL", 2.0));
        var first = buffer.apply(List.of(target("AAPL", 16.0, "200", "0")), gate, RATE);
        assertThat(first.aims().get("AAPL")).isEqualByComparingTo(new BigDecimal("6.556780"));
        assertThat(first.targets().get(0).deltaQty()).isEqualByComparingTo(new BigDecimal("6.556780"));
        assertThat(first.traded()).isEqualTo(1);
    }

    @Test
    void aCheapNameKeepsTheConventionBecauseTheWidthIsOneWay() {
        PositionBuffer buffer = new PositionBuffer(0.10);
        var gate = gateAt(8.0, 5.0, java.util.Map.of("AAPL", 0.2));
        // 2 x 0.2 / 8.0 = 0.05 — NARROWER than the convention, so the convention stands and this can
        // never add a trade the desk was not already going to make.
        assertThat(buffer.widthFor("AAPL", gate, PositionBuffer.passingEdgeBps(gate))).isEqualTo(0.10);
    }

    @Test
    void anExpensiveNameIsCappedAtOneAveragePosition() {
        PositionBuffer buffer = new PositionBuffer(0.10);
        var gate = gateAt(8.0, 5.0, java.util.Map.of("GOOGL", 20.11));
        // 2 x 20.11 / 8.0 = 5.0275 ⇒ capped at 1.0: the desk will not chase a name whose round trip
        // eats the edge, but the cap keeps the band a position rather than an unbounded number.
        assertThat(buffer.widthFor("GOOGL", gate, PositionBuffer.passingEdgeBps(gate))).isEqualTo(1.0);
    }

    @Test
    void aNameNeverFilledIsChargedTheDeskBlendExactlyAsTheGateCharsIt() {
        PositionBuffer buffer = new PositionBuffer(0.10);
        var gate = gateAt(8.0, 1.48, java.util.Map.of("AAPL", 2.0));
        // NFLX is absent from the measured map ⇒ the blend, 2 x 1.48 / 8.0 = 0.37.
        assertThat(buffer.widthFor("NFLX", gate, PositionBuffer.passingEdgeBps(gate))).isEqualTo(0.37);
    }

    @Test
    void withoutAMeasurementTheConventionStandsUnchanged() {
        PositionBuffer buffer = new PositionBuffer(0.10);
        // No gate at all.
        assertThat(buffer.widthFor("AAPL", null, 0.0)).isEqualTo(0.10);
        // A gate with no PASSING source states no expectancy the desk may trade on.
        var noPass = new EdgeGate.Decision(false, 2.0, "nothing clears",
                List.of(new EdgeGate.SourceEdge("trend", 500L, 33L, -4.69, 1.07, -6.69, -4.37, 1.0, false)),
                java.util.Map.of("AAPL", 2.0), new EdgeGate.Params(2, 2.0), 900L);
        assertThat(PositionBuffer.passingEdgeBps(noPass)).isEqualTo(0.0);
        assertThat(buffer.widthFor("AAPL", noPass, PositionBuffer.passingEdgeBps(noPass))).isEqualTo(0.10);
        // A measured cost of zero (or a price improvement) asserts no width either.
        var freeCost = gateAt(8.0, 0.0, java.util.Map.of("AAPL", 0.0));
        assertThat(buffer.widthFor("AAPL", freeCost, PositionBuffer.passingEdgeBps(freeCost)))
                .isEqualTo(0.10);
    }

    @Test
    void aWideBufferStillNeverBuffersAnExit() {
        PositionBuffer buffer = new PositionBuffer(0.10);
        var gate = gateAt(8.0, 5.0, java.util.Map.of("AAPL", 2.0)); // width 0.50
        buffer.apply(List.of(target("AAPL", 16.0, "200", "40")), gate, RATE);
        // The name is planned FLAT — the ADR-0086 cut / ADR-0065 unwind shape. The aim snaps to zero
        // and the whole 40 is sold this cycle, no matter how wide the band would have been.
        var exit = buffer.apply(List.of(target("AAPL", 0.0, "0", "40")), gate, RATE);
        assertThat(exit.targets().get(0).deltaQty()).isEqualByComparingTo(new BigDecimal("-40.000000"));
    }

    @Test
    void disabledBufferIsNotThisClassAndZeroFractionTradesTheWholeGapToTheAim() {
        PositionBuffer buffer = new PositionBuffer(0.0);
        var result = buffer.apply(List.of(target("AAPL", -9.64, "-142.319300", "-7")), null, RATE);
        // With a zero buffer the policy degenerates to "trade to the aim", i.e. exactly ADR-0080.
        assertThat(result.targets().get(0).deltaQty()).isEqualByComparingTo(new BigDecimal("-4.436294"));
    }
}
