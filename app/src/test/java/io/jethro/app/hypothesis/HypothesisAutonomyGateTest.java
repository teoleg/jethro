package io.jethro.app.hypothesis;

import io.jethro.domain.Side;
import io.jethro.trading.algo.hypothesis.Hypothesis;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0049 hard gate: an AI/news thesis may become an order ONLY when the deterministic OOS backtest
 * supports the name. Fail-closed — a null backtest (name never measured / OOS skipped) is NOT support,
 * so the model never originates an order without a completed deterministic measurement.
 */
class HypothesisAutonomyGateTest {

    private static HypothesisEvaluator.Evaluated withBacktest(HypothesisEvaluator.Backtest bt) {
        var h = new Hypothesis("h", "AAPL", Side.BUY, Hypothesis.Horizon.SWING,
                Hypothesis.Conviction.HIGH, "thesis", List.of(), null);
        return new HypothesisEvaluator.Evaluated(h, HypothesisEvaluator.Verdict.ADMISSIBLE, "AI",
                new BigDecimal("10"), new BigDecimal("190"), "ok", bt);
    }

    @Test
    void supportedNameIsEligible() {
        var bt = new HypothesisEvaluator.Backtest(new BigDecimal("120"), 8, true); // positive median, traded
        assertTrue(HypothesisLifecycle.deterministicallySupported(withBacktest(bt)));
    }

    @Test
    void unsupportedNameIsBlocked() {
        var bt = new HypothesisEvaluator.Backtest(new BigDecimal("-40"), 6, false); // measured, no edge
        assertFalse(HypothesisLifecycle.deterministicallySupported(withBacktest(bt)));
    }

    @Test
    void unmeasuredNameFailsClosed() {
        // No backtest for the instrument (never measured, or the OOS run was skipped this cycle):
        // ADR-0049 is fail-closed — that is NOT support, so no AI order.
        assertFalse(HypothesisLifecycle.deterministicallySupported(withBacktest(null)));
    }
}
