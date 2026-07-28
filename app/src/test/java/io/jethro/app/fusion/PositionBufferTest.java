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
 *   gap             = −11.436294 − (−7)      =  −4.436294
 *   |gap| = 4.436294 <= 14.763413            ⇒  NO ORDER
 * </pre>
 * The ADR-0080 policy sold 4 shares here, every cycle, for as long as the target stayed out of reach.
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

    @Test
    void theWorkedExampleIsInsideTheBufferAndTradesNothing() {
        PositionBuffer buffer = new PositionBuffer(0.10);
        var result = buffer.apply(List.of(target("AAPL", -9.64, "-142.319300", "-7")), null, RATE);
        // aim = −7 + a(−142.3193 + 7); a = 1 − e^(−30/900) = 0.0327839…
        assertThat(result.aims().get("AAPL")).isEqualByComparingTo(new BigDecimal("-11.436294"));
        assertThat(result.targets().get(0).deltaQty()).isEqualByComparingTo("0");
        assertThat(result.insideBuffer()).isEqualTo(1);
        assertThat(result.traded()).isZero();
    }

    @Test
    void theOldPolicyWouldHaveTradedTheSameNameEveryCycle() {
        // The ADR-0055 band is |target| x 0.5 = 71.159650 against a gap of 135.319300 — it cannot bind,
        // so the desk sells the full rated fraction. This is the order ADR-0094 suppresses.
        BigDecimal delta = TargetPlanner.orderDelta(new BigDecimal("-142.319300"), new BigDecimal("-7"),
                0.5, RATE);
        assertThat(delta).isEqualByComparingTo(new BigDecimal("-4.436294"));
    }

    @Test
    void onceTheAimHasDriftedItTradesToTheBufferEdgeNotToTheAim() {
        PositionBuffer buffer = new PositionBuffer(0.10);
        // Same name, but the desk's intent has already accumulated to −40 against a −7 holding.
        // gap = −33, band = 14.763413 ⇒ trade −(33 − 14.763413) = −18.236587, leaving the position
        // exactly one band short of the aim rather than at it.
        assertThat(PositionBuffer.bufferedDelta(new BigDecimal("-40"), new BigDecimal("-7"),
                new BigDecimal("14.763413"), new BigDecimal("-142.319300"), RATE))
                .isEqualByComparingTo(new BigDecimal("-18.236587"));
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
        // Re-armed with a fresh view, the aim restarts from the (now flat) book — not from the old intent.
        var again = buffer.apply(List.of(target("AAPL", -9.64, "-142.319300", "0")), null, RATE);
        assertThat(again.aims().get("AAPL")).isEqualByComparingTo(new BigDecimal("-4.665782"));
        assertThat(again.targets().get(0).deltaQty()).isEqualByComparingTo("0");
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

    // --- ADR-0118: a wrong-side holding under a shut gate is an exit, not a rebalance ---------------

    /** 30 s cycle against the 3600 s base horizon — the rung the live gate reported. a = 0.008298755… */
    private static final double HOUR_RATE = TargetPlanner.adjustmentRateFor(30, 3600);

    private static EdgeGate.Decision shutGate() {
        return new EdgeGate.Decision(false, 0.2334, "no measured edge", List.of(),
                java.util.Map.of(), new EdgeGate.Params(30, 2.0, 3), 3600L);
    }

    /**
     * The live AAPL plan of 2026-07-28, worked by hand:
     * <pre>
     *   averagePosition = 6.031064 x 10 / 0.436598559661783 = 138.137520
     *   band            = 0.10 x 138.137520                 =  13.813752
     *   a               = 1 - e^(-30/3600)                  =   0.008298755…
     *   aim (raw)       = -1 + a x (6.031064 + 1)           =  -0.941652   (opposes the target)
     *   aim (ADR-0102)  = 0                                  the desk intends to hold nothing
     *   gap             = 0 - (-1)                          =  +1.000000
     *   |gap| = 1.000000 <= 13.813752  =>  delta 0 — every cycle, indefinitely
     * </pre>
     * The desk was short a name its own forecast wanted long, could not rebuild it (gate shut) and could
     * not close it either. The exit is now worked in full: +1.000000, i.e. flat.
     */
    @Test
    void aWrongSideHoldingUnderAShutGateIsExitedNotFrozen() {
        PositionBuffer buffer = new PositionBuffer(0.10);
        assertThat(buffer.band(new BigDecimal("6.031064"), 0.436598559661783, new BigDecimal("-1")))
                .isEqualByComparingTo(new BigDecimal("13.813752"));
        var result = buffer.apply(List.of(target("AAPL", 0.436598559661783, "6.031064", "-1")),
                shutGate(), HOUR_RATE);
        assertThat(result.targets().get(0).deltaQty()).isEqualByComparingTo(new BigDecimal("1.000000"));
        // Intent and book agree at flat, so the next cycle has nothing left to do.
        assertThat(result.aims().get("AAPL")).isEqualByComparingTo("0");
    }

    /** Having exited, the desk stays flat — the shut gate forbids rebuilding, so there is no round trip. */
    @Test
    void theExitIsNotReopenedWhileTheGateStaysShut() {
        PositionBuffer buffer = new PositionBuffer(0.10);
        buffer.apply(List.of(target("AAPL", 0.436598559661783, "6.031064", "-1")), shutGate(), HOUR_RATE);
        var next = buffer.apply(List.of(target("AAPL", 0.436598559661783, "6.031064", "0")),
                shutGate(), HOUR_RATE);
        assertThat(next.targets().get(0).deltaQty()).isEqualByComparingTo("0");
    }

    /**
     * The ADR-0090 churn case is untouched: with the gate OPEN the desk can rebuild, so a forecast that
     * has flipped against the holding is still a rebalance — buffered and rated, never liquidated.
     */
    @Test
    void anOpenGateStillRebalancesAWrongSideHoldingRatherThanLiquidatingIt() {
        PositionBuffer buffer = new PositionBuffer(0.10);
        var open = gateAt(8.0, 5.0, java.util.Map.of("AAPL", 2.0));
        var result = buffer.apply(List.of(target("AAPL", 0.436598559661783, "6.031064", "-1")),
                open, HOUR_RATE);
        assertThat(result.targets().get(0).deltaQty()).isEqualByComparingTo("0");
    }

    /** Strictly one-way: the branch resolves to flat, so it can only ever take exposure off. */
    @Test
    void theTrappedExitOnlyEverReducesExposure() {
        PositionBuffer buffer = new PositionBuffer(0.10);
        // Long 3 against a target of -20: the intent is clamped flat, and the exit sells exactly 3.
        var result = buffer.apply(List.of(target("JNJ", -1.5, "-20.000000", "3")), shutGate(), HOUR_RATE);
        assertThat(result.targets().get(0).deltaQty()).isEqualByComparingTo(new BigDecimal("-3.000000"));
        assertThat(result.aims().get("JNJ")).isEqualByComparingTo("0");
    }

    /** A name the desk does not hold is still never opened by a shut gate. */
    @Test
    void aShutGateStillOpensNothingWhenFlat() {
        PositionBuffer buffer = new PositionBuffer(0.10);
        var result = buffer.apply(List.of(target("GOOG", -5.85, "-77.409630", "0")), shutGate(), HOUR_RATE);
        assertThat(result.targets().get(0).deltaQty()).isEqualByComparingTo("0");
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
    void anInvertedIntentIsUnwoundAtTheDerivedRateNotDumped() {
        PositionBuffer buffer = new PositionBuffer(0.10);
        // ADR-0107. Long 104 while the target is −219.420787: ADR-0102 clamps intent to flat, but the
        // target is ALIVE, so this is a change of view and not a cut. The move is buffered and its
        // unwinding half is rated:
        //   averagePosition = 219.420787 x 10 / 12.64 = 173.592395 ; band = 17.359240
        //   stepped         = 104 + a(−219.420787 − 104) = 93.397005 → clamped to 0 (ADR-0102)
        //   gap             = 0 − 104 = −104.000000 ; |gap| > band
        //   edge            = −(104.000000 − 17.359240) = −86.640760      (to the near buffer edge)
        //   all of it unwinds the holding ⇒ x a = −86.640760 x 0.0327838995179941 = −2.840422
        var cut = buffer.apply(List.of(target("JNJ", -12.64, "-219.420787", "104")), null, RATE);
        assertThat(cut.aims().get("JNJ")).isEqualByComparingTo("0");
        assertThat(cut.targets().get(0).deltaQty()).isEqualByComparingTo(new BigDecimal("-2.840422"));
        // Next cycle the aim path restarts from flat and runs to the new side, so the gap keeps
        // growing and the unwind cannot stall: aim = 0 + a(−219.420787) = −7.193469 against a book
        // still long 101 ⇒ gap −108.193469, edge −90.834229, x a = −2.977900.
        var next = buffer.apply(List.of(target("JNJ", -12.64, "-219.420787", "101")), null, RATE);
        assertThat(next.aims().get("JNJ")).isEqualByComparingTo(new BigDecimal("-7.193469"));
        assertThat(next.targets().get(0).deltaQty()).isEqualByComparingTo(new BigDecimal("-2.977900"));
    }

    @Test
    void aControlOrderedCutStillCrossesInFullOnTheSameHolding() {
        PositionBuffer buffer = new PositionBuffer(0.10);
        // The ADR-0107 distinction: the SAME long 104, but now a control (chandelier stop, orphan
        // unwind, silenced source) has planned the name FLAT. That is an exit and is neither buffered
        // nor rated — the whole position goes, this cycle, exactly as ADR-0090 works it.
        var exit = buffer.apply(List.of(target("JNJ", 0.0, "0", "104")), null, RATE);
        assertThat(exit.aims().get("JNJ")).isEqualByComparingTo("0");
        assertThat(exit.targets().get(0).deltaQty()).isEqualByComparingTo(new BigDecimal("-104.000000"));
    }

    @Test
    void theViewChangeUnwindIsStrictlyOneWay() {
        // It may only ever trade LESS than the unbuffered dump, never more and never the other way:
        // |delta| <= |aim − held| and the sign is the gap's, on every combination.
        for (String h : List.of("104", "-104", "7", "-7", "0")) {
            for (String t : List.of("-219.420787", "17285.020487", "-14.766000")) {
                for (String a : List.of("0", "-7.193469", "6.557800")) {
                    BigDecimal held = new BigDecimal(h);
                    BigDecimal aim = new BigDecimal(a);
                    BigDecimal gap = aim.subtract(held);
                    BigDecimal delta = PositionBuffer.bufferedDelta(aim, held,
                            new BigDecimal("17.359240"), new BigDecimal(t), RATE);
                    assertThat(delta.abs()).isLessThanOrEqualTo(gap.abs());
                    if (delta.signum() != 0) {
                        assertThat(delta.signum()).isEqualTo(gap.signum());
                    }
                }
            }
        }
    }

    @Test
    void aSameSideReductionKeepsItsUnratedSpeed() {
        // ADR-0107 touches only the gap that crossed flat in one step. An intent that shrank on its
        // OWN side arrived there by a rated aim step, so de-risking there is as fast as it ever was:
        // gap = −40 − (−120) = +80, band 17.359240 ⇒ +62.640760, unrated.
        assertThat(PositionBuffer.bufferedDelta(new BigDecimal("-40"), new BigDecimal("-120"),
                new BigDecimal("17.359240"), new BigDecimal("-219.420787"), RATE))
                .isEqualByComparingTo(new BigDecimal("62.640760"));
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
    void aGapTheConventionWouldHaveTradedIsInsideTheMeasuredBuffer() {
        PositionBuffer buffer = new PositionBuffer(0.10);
        var gate = gateAt(8.0, 5.0, java.util.Map.of("AAPL", 2.0));
        // a = 1 - e^(-30/900) = 0.0327838995…; from a flat book the aim path is
        //   cycle 1: 0 + a(200 - 0)             =  6.557800
        //   cycle 2: 6.557800 + a(200 - 6.5578) = 12.898603
        // and the book is still flat, so the gap is +12.898603 — OUTSIDE Carver's 12.500000 band,
        // INSIDE the measured 62.500000 one.
        buffer.apply(List.of(target("AAPL", 16.0, "200", "0")), gate, RATE);
        var measured = buffer.apply(List.of(target("AAPL", 16.0, "200", "0")), gate, RATE);
        assertThat(measured.aims().get("AAPL")).isEqualByComparingTo(new BigDecimal("12.898603"));
        assertThat(measured.targets().get(0).deltaQty()).isEqualByComparingTo("0");
        assertThat(measured.insideBuffer()).isEqualTo(1);

        // The same two cycles with no measurement: Carver's 0.10 trades the 12.898603 - 12.500000 =
        // 0.398603 that lies beyond its near edge.
        PositionBuffer unmeasured = new PositionBuffer(0.10);
        unmeasured.apply(List.of(target("AAPL", 16.0, "200", "0")), null, RATE);
        var conventional = unmeasured.apply(List.of(target("AAPL", 16.0, "200", "0")), null, RATE);
        assertThat(conventional.targets().get(0).deltaQty())
                .isEqualByComparingTo(new BigDecimal("0.398603"));
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
