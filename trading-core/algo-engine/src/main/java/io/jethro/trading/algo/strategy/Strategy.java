package io.jethro.trading.algo.strategy;

import java.math.BigDecimal;
import java.util.List;

/**
 * A deterministic signal engine: observations in, trade candidates out (advisory only —
 * ADR-0018/0019 downstream decide execution). Both the live {@code StrategyLifecycle} and
 * the backtest engine drive implementations through this port, so a new algo automatically
 * gets the SAME guardrails, sizing, cost model and out-of-sample harness as the first one —
 * the harness adjudicates between algos, never the other way around.
 */
public interface Strategy {

    List<TradeSignal> evaluate(List<Observation> observations);

    /** Short algo name for logs/UI (e.g. "momentum", "mean-reversion"). */
    String name();

    /**
     * One instrument's current mark for the strategy to consider, plus {@code relativeVolume} —
     * recent ÷ baseline traded volume (ADR-0033), &gt;1 a surge, &lt;1 thin. Defaults to 1.0
     * (neutral) via the 3-arg constructor, so a caller without volume (the backtest tape) leaves
     * volume-confirmation a no-op.
     */
    record Observation(String instrumentId, BigDecimal price, boolean stale, double relativeVolume) {
        public Observation(String instrumentId, BigDecimal price, boolean stale) {
            this(instrumentId, price, stale, 1.0);
        }
    }
}
