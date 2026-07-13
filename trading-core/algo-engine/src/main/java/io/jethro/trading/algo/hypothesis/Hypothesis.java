package io.jethro.trading.algo.hypothesis;

import io.jethro.domain.Side;

import java.util.List;

/**
 * A structured, <b>number-free</b> trading thesis proposed by the LLM layer (ADR-0022).
 * This is the hard boundary that keeps invariant 7 / ADR-0016 intact: the model emits a
 * direction and an ordinal conviction — never a size, price, expected return, or any number
 * that feeds a position, PnL, or risk figure. The deterministic quant layer
 * ({@code HypothesisEvaluator}) turns an admissible hypothesis into a sized, guardrailed
 * candidate; {@code conviction} is used only to prioritise, never as a multiplier in sizing.
 *
 * @param instrumentId the instrument the thesis is about (validated against the master).
 * @param direction    BUY = bullish/long view, SELL = bearish/short view.
 * @param horizon       expected holding horizon (ordinal label, not a number).
 * @param conviction    ordinal strength — for ordering/prioritisation only.
 * @param thesis        one-sentence rationale, in the model's words.
 * @param sources       ids of the narrative items that informed it (audit trail).
 */
public record Hypothesis(String hypothesisId, String instrumentId, Side direction,
                         Horizon horizon, Conviction conviction, String thesis, List<String> sources) {

    public enum Horizon { INTRADAY, SWING, POSITION }

    public enum Conviction { LOW, MEDIUM, HIGH }
}
