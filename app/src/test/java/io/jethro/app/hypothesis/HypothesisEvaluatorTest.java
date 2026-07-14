package io.jethro.app.hypothesis;

import io.jethro.app.backtest.BacktestResult;
import io.jethro.app.strategy.StrategyProperties;
import io.jethro.domain.Side;
import io.jethro.trading.algo.hypothesis.Hypothesis;
import io.jethro.trading.riskpnl.InstrumentRef;
import io.jethro.trading.riskpnl.InstrumentRefSource;
import io.jethro.trading.riskpnl.PreTradeGuardrail;
import io.jethro.trading.riskpnl.RiskLimits;
import io.jethro.trading.riskpnl.RiskLimitSource;
import io.jethro.trading.riskpnl.RiskProjection;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exact-value tests for the deterministic quant layer: the model gives a direction, this
 * code owns every number. Sizing = target / (price × multiplier), floored (finance-math).
 */
class HypothesisEvaluatorTest {

    private final InstrumentRefSource refs = id -> Optional.ofNullable(Map.of(
            "AAPL", new InstrumentRef("AAPL", "EQUITY", "USD", new BigDecimal("1")),
            "ES", new InstrumentRef("ES", "FUTURE", "USD", new BigDecimal("50"))
    ).get(id));

    // target 25,000; per-class caps default 2×target = 50,000 (no FUTURE override here).
    private final StrategyProperties sizing = new StrategyProperties(
            true, 5, 24, 2.5, new BigDecimal("2"), new BigDecimal("25000"),
            "ALPHA", Map.of("EQUITY", "ALPHA", "FUTURE", "MACRO"),
            false, 60, null, null, null, Map.of(), false, null, null, true, null, null);

    private static Hypothesis h(String instrument, Side dir) {
        return new Hypothesis("h1", instrument, dir, Hypothesis.Horizon.SWING,
                Hypothesis.Conviction.MEDIUM, "thesis", List.of());
    }

    private HypothesisEvaluator evaluator(RiskLimitSource limits, RiskProjection projection) {
        return new HypothesisEvaluator(refs, new PreTradeGuardrail(projection, limits), sizing, "AI");
    }

    @Test
    void admissibleSizesToTargetNotionalAndRoutesToTheAiSleeve() {
        var eval = evaluator(book -> RiskLimits.none(), new RiskProjection(refs));
        // 25,000 / (190 × 1) = 131.57 → floor 131; the AI sleeve trades its own book, not the
        // strategy's — so the momentum algo can't flatten it.
        var e = eval.evaluate(h("AAPL", Side.BUY), Map.of("AAPL", new BigDecimal("190")));
        assertEquals(HypothesisEvaluator.Verdict.ADMISSIBLE, e.verdict());
        assertEquals("AI", e.book());
        assertEquals(0, new BigDecimal("131").compareTo(e.quantity()));
    }

    @Test
    void measuredVolMakesSizingVolTargeted() {
        // AAPL measured σ = 1.8%/day, budget $250/day → notional 250/0.018 = 13,888.88;
        // qty = 13,888.88 / 190 = 73.09 → floor 73 (vs 131 at the flat 25k target).
        io.jethro.app.risk.InstrumentVolSource vols = id -> "AAPL".equals(id)
                ? java.util.Optional.of(new BigDecimal("0.018")) : java.util.Optional.empty();
        var eval = new HypothesisEvaluator(refs, new PreTradeGuardrail(new RiskProjection(refs),
                book -> RiskLimits.none()), sizing, "AI", vols);
        var e = eval.evaluate(h("AAPL", Side.BUY), Map.of("AAPL", new BigDecimal("190")));
        assertEquals(HypothesisEvaluator.Verdict.ADMISSIBLE, e.verdict());
        assertEquals(0, new BigDecimal("73").compareTo(e.quantity()),
                "vol-targeted: 250/0.018 = 13,888.88 → 73 shares @ 190");
    }

    @Test
    void oneContractOverTheClassCapIsUnsizeable() {
        var eval = evaluator(book -> RiskLimits.none(), new RiskProjection(refs));
        // 1 ES = 5450 × 50 = 272,500 > 50,000 cap → unsizeable, never rounded up.
        var e = eval.evaluate(h("ES", Side.BUY), Map.of("ES", new BigDecimal("5450")));
        assertEquals(HypothesisEvaluator.Verdict.UNSIZEABLE, e.verdict());
        assertEquals("AI", e.book());
        assertNull(e.quantity());
    }

    @Test
    void unknownInstrumentIsRejected() {
        var eval = evaluator(book -> RiskLimits.none(), new RiskProjection(refs));
        var e = eval.evaluate(h("ZZZZ", Side.BUY), Map.of("ZZZZ", new BigDecimal("10")));
        assertEquals(HypothesisEvaluator.Verdict.UNKNOWN_INSTRUMENT, e.verdict());
    }

    @Test
    void noMarkCannotBeValued() {
        var eval = evaluator(book -> RiskLimits.none(), new RiskProjection(refs));
        var e = eval.evaluate(h("AAPL", Side.BUY), Map.of()); // no price for AAPL
        assertEquals(HypothesisEvaluator.Verdict.NO_MARK, e.verdict());
    }

    @Test
    void backtestAnnotationReflectsInstrumentEdge() {
        var eval = evaluator(book -> RiskLimits.none(), new RiskProjection(refs));
        var marks = Map.of("AAPL", new BigDecimal("190"));
        // Net-positive on trades → supported.
        var supported = eval.evaluate(h("AAPL", Side.BUY), marks, Map.of("AAPL",
                new BacktestResult.InstrumentResult("AAPL", 8, new BigDecimal("1200"), new BigDecimal("50"), BigDecimal.ZERO)));
        assertEquals(HypothesisEvaluator.Verdict.ADMISSIBLE, supported.verdict());
        assertTrue(supported.backtest().supports());
        // Losing on this name → not supported.
        var unsupported = eval.evaluate(h("AAPL", Side.BUY), marks, Map.of("AAPL",
                new BacktestResult.InstrumentResult("AAPL", 8, new BigDecimal("-1200"), BigDecimal.ZERO, BigDecimal.ZERO)));
        assertTrue(!unsupported.backtest().supports());
        // No backtest for the instrument → null annotation, still evaluated.
        var none = eval.evaluate(h("AAPL", Side.BUY), marks, Map.of());
        assertNull(none.backtest());
    }

    @Test
    void guardrailBreachIsBlockedNotSurfaced() {
        var projection = new RiskProjection(refs);
        projection.applyMark("AAPL", new BigDecimal("190"), 0); // guardrail values exposure off its own marks
        // Concentration cap 1,000 « 131 × 190 = 24,890 → the pre-trade guardrail rejects.
        RiskLimitSource limits = book -> new RiskLimits(null, null, null, new BigDecimal("1000"));
        var e = evaluator(limits, projection).evaluate(h("AAPL", Side.BUY), Map.of("AAPL", new BigDecimal("190")));
        assertEquals(HypothesisEvaluator.Verdict.BLOCKED, e.verdict());
    }
}
