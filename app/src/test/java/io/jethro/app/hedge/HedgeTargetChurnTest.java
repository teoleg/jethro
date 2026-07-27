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
