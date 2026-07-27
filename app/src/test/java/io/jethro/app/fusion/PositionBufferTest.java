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
                new BigDecimal("14.763413")))
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
        // aim −20.000000, gap +100.171591, band 14.766000 ⇒ +85.405591, and reduce-only leaves it whole
        // because it never crosses through flat.
        assertThat(result.targets().get(0).deltaQty()).isEqualByComparingTo(new BigDecimal("85.405591"));
    }

    @Test
    void aNameThatLeavesTheBookLeavesNoIntentBehind() {
        PositionBuffer buffer = new PositionBuffer(0.10);
        buffer.apply(List.of(target("AAPL", -9.64, "-142.319300", "-7")), null, RATE);
        var next = buffer.apply(List.of(target("MSFT", 8.0, "100", "0")), null, RATE);
        assertThat(next.aims()).containsOnlyKeys("MSFT");
    }

    @Test
    void disabledBufferIsNotThisClassAndZeroFractionTradesTheWholeGapToTheAim() {
        PositionBuffer buffer = new PositionBuffer(0.0);
        var result = buffer.apply(List.of(target("AAPL", -9.64, "-142.319300", "-7")), null, RATE);
        // With a zero buffer the policy degenerates to "trade to the aim", i.e. exactly ADR-0080.
        assertThat(result.targets().get(0).deltaQty()).isEqualByComparingTo(new BigDecimal("-4.436294"));
    }
}
