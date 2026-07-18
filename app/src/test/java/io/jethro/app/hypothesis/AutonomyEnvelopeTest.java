package io.jethro.app.hypothesis;

import io.jethro.domain.Side;
import io.jethro.trading.algo.hypothesis.Hypothesis;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bounded autonomy gated on the AI's MEASURED track record (ADR-0027): probation size while
 * the record builds, full size only while measured outcome P&L is positive, revoked when the
 * record is non-positive. The momentum backtest no longer gates (it measured a different
 * strategy — the category error that silently revoked all autonomy).
 */
class AutonomyEnvelopeTest {

    private static final AutonomyEnvelope.TrackRecord NO_RECORD = AutonomyEnvelope.TrackRecord.EMPTY;
    private static final AutonomyEnvelope.TrackRecord GOOD_RECORD =
            new AutonomyEnvelope.TrackRecord(12, new BigDecimal("850"));
    private static final AutonomyEnvelope.TrackRecord BAD_RECORD =
            new AutonomyEnvelope.TrackRecord(12, new BigDecimal("-300"));

    private static Hypothesis hyp(Hypothesis.Conviction conv) {
        return new Hypothesis("h", "AAPL", Side.BUY, Hypothesis.Horizon.SWING, conv, "thesis", List.of());
    }

    private static HypothesisEvaluator.Evaluated evaluated(Hypothesis.Conviction conv, String qty, String price) {
        return new HypothesisEvaluator.Evaluated(hyp(conv), HypothesisEvaluator.Verdict.ADMISSIBLE, "AI",
                new BigDecimal(qty), new BigDecimal(price), "ok", null);
    }

    /** cap 10000, probation 2500, record needed: 10. */
    private static AutonomyEnvelope env(String minConv, List<String> whitelist) {
        return new AutonomyEnvelope(new HypothesisProperties.Autonomy(
                true, minConv, new BigDecimal("10000"), 300L, whitelist,
                new BigDecimal("2500"), 10));
    }

    @Test
    void probationResizesDownToBuildTheRecord() {
        // Quant sized 40 @ 190 = 7600 notional; probation cap 2500 → resized to ⌊2500/190⌋ = 13.
        var d = env("MEDIUM", List.of()).decide(evaluated(Hypothesis.Conviction.HIGH, "40", "190"),
                BigDecimal.ONE, NO_RECORD);
        assertTrue(d.allowed());
        assertTrue(d.probation());
        assertEquals(0, new BigDecimal("13").compareTo(d.quantity()), "resized to probation size");
    }

    @Test
    void earnedRecordTradesFullSizeWithinTheCap() {
        var d = env("MEDIUM", List.of()).decide(evaluated(Hypothesis.Conviction.HIGH, "40", "190"),
                BigDecimal.ONE, GOOD_RECORD);
        assertTrue(d.allowed());
        assertFalse(d.probation());
        assertEquals(0, new BigDecimal("40").compareTo(d.quantity()), "full quant size");
    }

    @Test
    void negativeRecordRevokesAutonomy() {
        var d = env("MEDIUM", List.of()).decide(evaluated(Hypothesis.Conviction.HIGH, "40", "190"),
                BigDecimal.ONE, BAD_RECORD);
        assertFalse(d.allowed());
        assertTrue(d.reason().contains("revoked"), "a losing record loses autonomy: " + d.reason());
    }

    @Test
    void earnedModeStillRejectsOverTheCap() {
        // 100 @ 190 = 19000 > 10000 cap — full size must be deliberate, not silently resized.
        var d = env("MEDIUM", List.of()).decide(evaluated(Hypothesis.Conviction.HIGH, "100", "190"),
                BigDecimal.ONE, GOOD_RECORD);
        assertFalse(d.allowed());
        assertTrue(d.reason().contains("exceeds autonomy cap"));
    }

    @Test
    void convictionAndWhitelistStillGateEveryPhase() {
        assertFalse(env("HIGH", List.of()).decide(evaluated(Hypothesis.Conviction.MEDIUM, "10", "190"),
                BigDecimal.ONE, GOOD_RECORD).allowed(), "below min conviction");
        assertFalse(env("MEDIUM", List.of("MSFT")).decide(evaluated(Hypothesis.Conviction.HIGH, "10", "190"),
                BigDecimal.ONE, NO_RECORD).allowed(), "AAPL not whitelisted");
    }

    @Test
    void probationTradesTheOneUnitMinimumWhenItCannotAffordMore() {
        // 1 ES = 5450 × 50 = 272,500 per unit ≫ 2500 probation. You can't trade a fraction of a
        // contract, so probation trades the 1-unit minimum rather than deadlocking on the name.
        var e = new HypothesisEvaluator.Evaluated(hyp(Hypothesis.Conviction.HIGH),
                HypothesisEvaluator.Verdict.ADMISSIBLE, "AI",
                new BigDecimal("3"), new BigDecimal("5450"), "ok", null);
        var d = env("MEDIUM", List.of()).decide(e, new BigDecimal("50"), NO_RECORD);
        assertTrue(d.allowed(), "probation must still execute the 1-unit minimum, not reject");
        assertEquals(0, BigDecimal.ONE.compareTo(d.quantity()), "sized to the 1-unit floor");
        assertTrue(d.probation(), "still flagged probation");
    }
}
