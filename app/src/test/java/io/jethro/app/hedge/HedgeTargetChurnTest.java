package io.jethro.app.hedge;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0098's estimator: an EWMA(λ=0.94) of the squared STEP in the raw hedge-target notional,
 * sampled no faster than the hedge cooldown. Every expectation below is a hand-computed exact
 * decimal — with λ = 0.94 the arithmetic stays checkable by eye.
 */
class HedgeTargetChurnTest {

    private static final long MINUTE = 60_000L;

    @Test
    void oscillatingTargetMeasuresItsOwnAmplitude() {
        // Samples 0 → 1000 → 0. Steps: d₁ = +1000 seeds v = 1000² = 1,000,000; d₂ = −1000 gives
        // v = 0.94·1,000,000 + 0.06·1,000,000 = 1,000,000 → σ = √1,000,000 = 1000.00 exactly.
        var churn = new HedgeTargetChurn(MINUTE);
        churn.observe("EQUITY", new BigDecimal("0"), 0);
        churn.observe("EQUITY", new BigDecimal("1000"), MINUTE);
        churn.observe("EQUITY", new BigDecimal("0"), 2 * MINUTE);
        assertEquals(0, new BigDecimal("1000.00").compareTo(churn.sigmaUsd("EQUITY").orElseThrow()));
    }

    @Test
    void unequalStepsDecayTheOlderOne() {
        // Samples 0 → 100 → 300. d₁ = 100 seeds v = 10,000; d₂ = 200 gives
        // v = 0.94·10,000 + 0.06·40,000 = 9,400 + 2,400 = 11,800 → σ = √11,800 = 108.6278… → 108.63.
        var churn = new HedgeTargetChurn(MINUTE);
        churn.observe("EQUITY", new BigDecimal("0"), 0);
        churn.observe("EQUITY", new BigDecimal("100"), MINUTE);
        churn.observe("EQUITY", new BigDecimal("300"), 2 * MINUTE);
        assertEquals(0, new BigDecimal("108.63").compareTo(churn.sigmaUsd("EQUITY").orElseThrow()));
    }

    @Test
    void aTargetThatDoesNotMoveHasNoChurn() {
        // The whole point of the one-way rule: a persistent, unmoving hedge target is shrunk by
        // nothing at all, so ADR-0098 cannot quietly stop the desk hedging a real exposure.
        var churn = new HedgeTargetChurn(MINUTE);
        churn.observe("EQUITY", new BigDecimal("500000"), 0);
        churn.observe("EQUITY", new BigDecimal("500000"), MINUTE);
        churn.observe("EQUITY", new BigDecimal("500000"), 2 * MINUTE);
        assertTrue(churn.sigmaUsd("EQUITY").isEmpty(), "zero variance publishes no σ");
    }

    @Test
    void oneStepIsNotYetAnEstimate() {
        var churn = new HedgeTargetChurn(MINUTE);
        churn.observe("EQUITY", new BigDecimal("0"), 0);
        churn.observe("EQUITY", new BigDecimal("1000"), MINUTE);
        assertTrue(churn.sigmaUsd("EQUITY").isEmpty(), "one squared difference is not a σ");
    }

    @Test
    void samplesInsideTheIntervalAreIgnored() {
        // A REST poll (or a 5s evaluation tick) must not shorten the step and understate σ: only
        // the samples at or beyond the cooldown spacing count. Here 0 → 1000 → 0 at 0/1/2 minutes
        // with three ignored offers in between reproduces the oscillating case exactly.
        var churn = new HedgeTargetChurn(MINUTE);
        churn.observe("EQUITY", new BigDecimal("0"), 0);
        churn.observe("EQUITY", new BigDecimal("999999"), 1_000);
        churn.observe("EQUITY", new BigDecimal("1000"), MINUTE);
        churn.observe("EQUITY", new BigDecimal("-999999"), MINUTE + 1_000);
        churn.observe("EQUITY", new BigDecimal("0"), 2 * MINUTE);
        assertEquals(0, new BigDecimal("1000.00").compareTo(churn.sigmaUsd("EQUITY").orElseThrow()));
    }

    // ---- ADR-0100: directional efficiency of the same sampled series ----

    @Test
    void aTargetGoingSomewhereEarnsFullTracking() {
        // Samples 0 → 1000 → 2000 → 3000, every step +1000. Both EWMAs see the identical magnitude
        // with identical weights, so drift = absStep at every reading:
        //   D₁ = 0.06·1000 = 60,      A₁ = 60
        //   D₂ = 0.94·60 + 60 = 116.4, A₂ = 116.4
        //   D₃ = 0.94·116.4 + 60 = 169.416, A₃ = 169.416   → E = 1.000000 exactly.
        var churn = new HedgeTargetChurn(MINUTE);
        churn.observe("EQUITY", new BigDecimal("0"), 0);
        churn.observe("EQUITY", new BigDecimal("1000"), MINUTE);
        churn.observe("EQUITY", new BigDecimal("2000"), 2 * MINUTE);
        churn.observe("EQUITY", new BigDecimal("3000"), 3 * MINUTE);
        assertEquals(0, BigDecimal.ONE.compareTo(churn.efficiencyRatio("EQUITY").orElseThrow()));
    }

    @Test
    void aPureRoundTripEarnsAlmostNoTracking() {
        // Samples 0 → 1000 → 0: the target ends where it started, so its net displacement is zero
        // and every unit traded chasing it would have been a round trip.
        //   D₂ = 0.94·60 + 0.06·(−1000) = 56.4 − 60 = −3.6
        //   A₂ = 0.94·60 + 0.06·1000    = 56.4 + 60 = 116.4
        //   E = 3.6 / 116.4 = 0.030927835… → 0.030928, which is (1−λ)/(1+λ) — the exponential
        //   estimator's floor for a perfect oscillation, exactly as it should be.
        var churn = new HedgeTargetChurn(MINUTE);
        churn.observe("EQUITY", new BigDecimal("0"), 0);
        churn.observe("EQUITY", new BigDecimal("1000"), MINUTE);
        churn.observe("EQUITY", new BigDecimal("0"), 2 * MINUTE);
        assertEquals(0, new BigDecimal("0.030928")
                .compareTo(churn.efficiencyRatio("EQUITY").orElseThrow()));
    }

    @Test
    void aPartlyDirectionalPathTracksKaufmansRatio() {
        // Samples 0 → 1000 → 0 → 1000. Kaufman's ratio over the same window is |1000 − 0| / 3000 =
        // 0.3333; the exponential form weights the newest step more and reads a shade higher:
        //   D₃ = 0.94·(−3.6) + 0.06·1000 = −3.384 + 60 = 56.616
        //   A₃ = 0.94·116.4 + 0.06·1000  = 109.416 + 60 = 169.416
        //   E = 56.616 / 169.416 = 0.33418331… → 0.334183.
        var churn = new HedgeTargetChurn(MINUTE);
        churn.observe("EQUITY", new BigDecimal("0"), 0);
        churn.observe("EQUITY", new BigDecimal("1000"), MINUTE);
        churn.observe("EQUITY", new BigDecimal("0"), 2 * MINUTE);
        churn.observe("EQUITY", new BigDecimal("1000"), 3 * MINUTE);
        assertEquals(0, new BigDecimal("0.334183")
                .compareTo(churn.efficiencyRatio("EQUITY").orElseThrow()));
    }

    @Test
    void oneStepPublishesNoEfficiency() {
        // A single step is perfectly "efficient" by construction and says nothing — until the
        // second one the advisor must track its target exactly as it did before ADR-0100.
        var churn = new HedgeTargetChurn(MINUTE);
        churn.observe("EQUITY", new BigDecimal("0"), 0);
        churn.observe("EQUITY", new BigDecimal("1000"), MINUTE);
        assertTrue(churn.efficiencyRatio("EQUITY").isEmpty());
    }

    @Test
    void axesAreMeasuredSeparately() {
        var churn = new HedgeTargetChurn(MINUTE);
        churn.observe("EQUITY", new BigDecimal("0"), 0);
        churn.observe("EQUITY", new BigDecimal("1000"), MINUTE);
        churn.observe("EQUITY", new BigDecimal("0"), 2 * MINUTE);
        assertTrue(churn.sigmaUsd("RATES").isEmpty(), "another axis has never been sampled");
        assertTrue(churn.sigmaUsd("EQUITY").isPresent());
    }
}
