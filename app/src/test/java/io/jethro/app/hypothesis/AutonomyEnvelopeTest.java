package io.jethro.app.hypothesis;

import io.jethro.domain.Side;
import io.jethro.trading.algo.hypothesis.Hypothesis;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bounded-autonomy envelope: admits only tight, backtest-supported, high-conviction orders. */
class AutonomyEnvelopeTest {

    private static Hypothesis hyp(Hypothesis.Conviction conv) {
        return new Hypothesis("h", "AAPL", Side.BUY, Hypothesis.Horizon.SWING, conv, "thesis", List.of());
    }

    private static HypothesisEvaluator.Evaluated evaluated(Hypothesis.Conviction conv, boolean supports,
                                                           String qty, String price) {
        var bt = new HypothesisEvaluator.Backtest(new BigDecimal("500"), 6, supports);
        return new HypothesisEvaluator.Evaluated(hyp(conv), HypothesisEvaluator.Verdict.ADMISSIBLE, "ALPHA",
                new BigDecimal(qty), new BigDecimal(price), "ok", bt);
    }

    private static HypothesisProperties.Autonomy cfg(String minConv, String cap, List<String> whitelist) {
        return new HypothesisProperties.Autonomy(true, minConv, new BigDecimal(cap), 300L, whitelist);
    }

    @Test
    void admitsSupportedHighConvictionWithinCap() {
        // 40 × 190 = 7,600 ≤ 10,000; HIGH ≥ HIGH; supported → admitted.
        var env = new AutonomyEnvelope(cfg("HIGH", "10000", List.of()));
        assertTrue(env.rejectionReason(evaluated(Hypothesis.Conviction.HIGH, true, "40", "190"), BigDecimal.ONE).isEmpty());
    }

    @Test
    void rejectsBelowMinimumConviction() {
        var env = new AutonomyEnvelope(cfg("HIGH", "10000", List.of()));
        assertTrue(env.rejectionReason(evaluated(Hypothesis.Conviction.MEDIUM, true, "40", "190"), BigDecimal.ONE).isPresent());
    }

    @Test
    void rejectsUnsupportedByBacktest() {
        var env = new AutonomyEnvelope(cfg("HIGH", "10000", List.of()));
        assertTrue(env.rejectionReason(evaluated(Hypothesis.Conviction.HIGH, false, "40", "190"), BigDecimal.ONE).isPresent());
    }

    @Test
    void rejectsOverTheNotionalCap() {
        // 100 × 190 = 19,000 > 10,000.
        var env = new AutonomyEnvelope(cfg("HIGH", "10000", List.of()));
        assertTrue(env.rejectionReason(evaluated(Hypothesis.Conviction.HIGH, true, "100", "190"), BigDecimal.ONE).isPresent());
    }

    @Test
    void whitelistRestrictsInstruments() {
        var offList = new AutonomyEnvelope(cfg("HIGH", "10000", List.of("MSFT")));
        assertTrue(offList.rejectionReason(evaluated(Hypothesis.Conviction.HIGH, true, "40", "190"), BigDecimal.ONE).isPresent());
        var onList = new AutonomyEnvelope(cfg("HIGH", "10000", List.of("AAPL")));
        assertFalse(onList.rejectionReason(evaluated(Hypothesis.Conviction.HIGH, true, "40", "190"), BigDecimal.ONE).isPresent());
    }
}
