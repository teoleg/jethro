package io.jethro.app.fusion;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * ADR-0086: the volatility-scaled trailing exit.
 *
 * <p><b>The worked example every test below is pinned to.</b> The σ sensor is fed 100 → 101 → 100, two
 * 1% steps, so σ of one sampling interval is exactly {@code |ln(1.01)| = 0.00995033…}. Sampling every
 * 30s and holding for 30s makes {@code σ_h = σ_Δ} (√(30/30) = 1), so at {@code k = 3} the trigger sits
 * at {@code 3 × 0.00995033… = 0.02985099…} — a 2.9851% retrace from the peak.
 *
 * <p>A SHORT of 0.3 ES opened at 5450 that runs to a trough of 5440 is then:
 * <pre>
 *   mark 5600 → excursion (5600 − 5440)/5440 = 0.02941176…  &lt; 0.02985099…  → HOLD
 *   mark 5605 → excursion (5605 − 5440)/5440 = 0.03033088…  &gt; 0.02985099…  → CUT
 * </pre>
 * and the cut trades the whole position back in one cycle: target 0, delta +0.300000.
 */
class TrailingRiskCutTest {

    private static final long HORIZON_S = 30;
    private static final long INTERVAL_S = 30;
    private static final FusionPlanner.Params PLAN =
            new FusionPlanner.Params(0.5, new BigDecimal("50000"), 0.5, 0.5);

    /** σ_Δ = |ln(1.01)| on a span-2 sensor — the anchor of the worked example above. */
    private static StreamVolatility measuredSigma(String instrument) {
        var vol = new StreamVolatility(new StreamVolatility.Params(2));
        vol.update(instrument, new BigDecimal("100"));
        vol.update(instrument, new BigDecimal("101"));
        vol.update(instrument, new BigDecimal("100"));
        return vol;
    }

    private static FusionPlanner.Target target(String instrument, String price, String currentQty,
                                               String deltaQty) {
        return new FusionPlanner.Target(instrument, -17.0, 2, 1.0, 1.0, new BigDecimal(price),
                new BigDecimal("-0.500000"), new BigDecimal(currentQty), new BigDecimal(deltaQty),
                List.of());
    }

    private static TrailingRiskCut.Result cycle(TrailingRiskCut cut, StreamVolatility vol, long now,
                                                FusionPlanner.Target t) {
        return cut.apply(List.of(t), now, HORIZON_S, INTERVAL_S, vol, PLAN);
    }

    @Test
    void theWorkedExampleCutsAtTheStatedThresholdAndNotBefore() {
        var vol = measuredSigma("ES");
        assertThat(vol.sigmaOver("ES", HORIZON_S, INTERVAL_S).getAsDouble())
                .isCloseTo(Math.abs(Math.log(1.01)), within(1e-12));
        var cut = new TrailingRiskCut(new TrailingRiskCut.Params(3.0));

        // Open the short at 5450, then let it run to its best level (a trough, for a short) at 5440.
        assertThat(cycle(cut, vol, 1_000, target("ES", "5450", "-0.300000", "-0.010000")).cuts()).isEmpty();
        assertThat(cycle(cut, vol, 31_000, target("ES", "5440", "-0.300000", "-0.010000")).cuts()).isEmpty();

        // 5600: 2.9412% given back against a 2.9851% trigger — inside the noise of the holding period.
        var held = cycle(cut, vol, 61_000, target("ES", "5600", "-0.300000", "-0.010000"));
        assertThat(held.cuts()).isEmpty();
        assertThat(held.targets().get(0).deltaQty()).isEqualByComparingTo("-0.010000"); // untouched

        // 5605: 3.0331% — the view is wrong by its own volatility. Cut to flat, in full, this cycle.
        var fired = cycle(cut, vol, 91_000, target("ES", "5605", "-0.300000", "-0.010000"));
        assertThat(fired.cuts()).hasSize(1);
        TrailingRiskCut.Cut c = fired.cuts().get(0);
        assertThat(c.instrument()).isEqualTo("ES");
        assertThat(c.side()).isEqualTo(-1);
        assertThat(c.peak()).isEqualByComparingTo("5440");
        assertThat(c.excursion()).isCloseTo(165.0 / 5440.0, within(1e-9));
        assertThat(c.threshold()).isCloseTo(3.0 * Math.abs(Math.log(1.01)), within(1e-9));
        assertThat(c.reArmAtMillis()).isEqualTo(91_000 + HORIZON_S * 1_000);

        FusionPlanner.Target after = fired.targets().get(0);
        assertThat(after.targetQty()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(after.deltaQty()).isEqualByComparingTo("0.300000"); // buy the whole short back
        assertThat(fired.stoppedNames()).isEqualTo(1);
    }

    @Test
    void aStoppedNameStaysFlatForOneHoldingHorizonAndThenReArms() {
        var vol = measuredSigma("ES");
        var cut = new TrailingRiskCut(new TrailingRiskCut.Params(3.0));
        cycle(cut, vol, 1_000, target("ES", "5440", "-0.300000", "-0.010000"));
        assertThat(cycle(cut, vol, 31_000, target("ES", "5605", "-0.300000", "-0.010000")).cuts()).hasSize(1);

        // Still inside the cooldown: even a fresh risk-increasing delta is refused and the target is flat.
        var during = cycle(cut, vol, 41_000, target("ES", "5605", "0.000000", "-0.020000"));
        assertThat(during.targets().get(0).targetQty()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(during.targets().get(0).deltaQty()).isEqualByComparingTo(BigDecimal.ZERO); // flat already
        assertThat(during.stoppedNames()).isEqualTo(1);
        assertThat(cut.activeCuts(41_000)).hasSize(1);

        // One holding horizon after the cut, the name is planned exactly as the book asks again.
        var after = cycle(cut, vol, 61_000, target("ES", "5605", "0.000000", "-0.020000"));
        assertThat(after.stoppedNames()).isZero();
        assertThat(after.targets().get(0).deltaQty()).isEqualByComparingTo("-0.020000");
        assertThat(cut.activeCuts(61_000)).isEmpty();
    }

    @Test
    void aLongIsCutOnTheSameRuleMeasuredFromItsHighWaterMark() {
        var vol = measuredSigma("MSFT");
        var cut = new TrailingRiskCut(new TrailingRiskCut.Params(3.0));
        cycle(cut, vol, 1_000, target("MSFT", "400.00", "10.000000", "1.000000"));
        cycle(cut, vol, 31_000, target("MSFT", "420.00", "10.000000", "1.000000")); // peak 420
        // 420 → 407.5 is 2.9762% given back: under the 2.9851% trigger.
        assertThat(cycle(cut, vol, 61_000, target("MSFT", "407.50", "10.000000", "1.000000")).cuts()).isEmpty();
        var fired = cycle(cut, vol, 91_000, target("MSFT", "407.00", "10.000000", "1.000000"));
        assertThat(fired.cuts()).hasSize(1);
        assertThat(fired.cuts().get(0).side()).isEqualTo(1);
        assertThat(fired.cuts().get(0).peak()).isEqualByComparingTo("420.00");
        assertThat(fired.targets().get(0).deltaQty()).isEqualByComparingTo("-10.000000");
    }

    @Test
    void aWinnerThatKeepsRunningIsNeverCut() {
        // The whole point of trailing from the peak rather than from entry: the trigger follows the
        // position up, so an uninterrupted 20% run never fires however far it travels.
        var vol = measuredSigma("MSFT");
        var cut = new TrailingRiskCut(new TrailingRiskCut.Params(3.0));
        long now = 1_000;
        for (int i = 0; i <= 20; i++) {
            var r = cycle(cut, vol, now, target("MSFT", String.valueOf(400 + i * 4), "10.000000", "1.000000"));
            assertThat(r.cuts()).isEmpty();
            now += 30_000;
        }
    }

    @Test
    void aNameWithNoMeasuredVolatilityIsNeverCut() {
        // No measurement, no claim (ADR-0016 / invariant 7): the book is left exactly as planned.
        var vol = new StreamVolatility(new StreamVolatility.Params(120)); // cold
        var cut = new TrailingRiskCut(new TrailingRiskCut.Params(3.0));
        cycle(cut, vol, 1_000, target("ES", "5440", "-0.300000", "-0.010000"));
        var r = cycle(cut, vol, 31_000, target("ES", "9999", "-0.300000", "-0.010000"));
        assertThat(r.cuts()).isEmpty();
        assertThat(r.targets().get(0).deltaQty()).isEqualByComparingTo("-0.010000");
    }

    @Test
    void aFlatNameIsNeverCutAndItsPeakIsForgotten() {
        var vol = measuredSigma("ES");
        var cut = new TrailingRiskCut(new TrailingRiskCut.Params(3.0));
        var r = cycle(cut, vol, 1_000, target("ES", "5440", "0.000000", "-0.010000"));
        assertThat(r.cuts()).isEmpty();
        assertThat(r.trackedNames()).isZero(); // pruned — the state map tracks the book, not history
        assertThat(cycle(cut, vol, 31_000, target("ES", "9999", "0.000000", "-0.010000")).cuts()).isEmpty();
    }

    @Test
    void flippingSideRestartsTheExcursionFromTheNewPositionsPrice() {
        var vol = measuredSigma("ES");
        var cut = new TrailingRiskCut(new TrailingRiskCut.Params(3.0));
        cycle(cut, vol, 1_000, target("ES", "5440", "-0.300000", "-0.010000"));   // short, trough 5440
        cycle(cut, vol, 31_000, target("ES", "5600", "0.300000", "0.010000"));    // now LONG at 5600
        // The old short's 160-point excursion must not carry over into the new long.
        var r = cycle(cut, vol, 61_000, target("ES", "5601", "0.300000", "0.010000"));
        assertThat(r.cuts()).isEmpty();
    }

    @Test
    void aCutCanOnlyEverTakeRiskOff() {
        // The structural guarantee: whatever the arithmetic, the delta opposes the position and never
        // exceeds it, so this control cannot lever the book up on the strength of an estimated σ.
        var vol = measuredSigma("ES");
        var cut = new TrailingRiskCut(new TrailingRiskCut.Params(3.0));
        cycle(cut, vol, 1_000, target("ES", "5440", "-0.300000", "-9.000000"));
        var fired = cycle(cut, vol, 31_000, target("ES", "5605", "-0.300000", "-9.000000"));
        FusionPlanner.Target after = fired.targets().get(0);
        assertThat(after.deltaQty().signum()).isEqualTo(1);                       // opposes the short
        assertThat(after.deltaQty().abs()).isEqualByComparingTo("0.300000");      // never through flat
        assertThat(after.currentQty().add(after.deltaQty())).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void anEmptyBookIsAcceptedWithoutComplaint() {
        var cut = new TrailingRiskCut(new TrailingRiskCut.Params(3.0));
        var r = cut.apply(List.of(), 1_000, HORIZON_S, INTERVAL_S, measuredSigma("ES"), PLAN);
        assertThat(r.targets()).isEmpty();
        assertThat(r.cuts()).isEmpty();
    }
}
