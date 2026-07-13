package io.jethro.trading.algo.hypothesis;

import java.util.List;
import java.util.Set;

/**
 * The snapshot the LLM reasons over to propose hypotheses (ADR-0022): current marks, the
 * narrative feed, and the current portfolio — the four input streams from the owner's
 * diagram (market data, news/earnings/econ, portfolio). Prices are decimal strings, never
 * doubles (invariant 1). {@code tradableInstruments} bounds the model's choice so a
 * hallucinated ticker is dropped, not traded.
 */
public record HypothesisContext(List<MarkView> marks, List<NarrativeItem> narrative,
                                List<PortfolioLine> portfolio, Set<String> tradableInstruments) {

    /** A current price the model may reference (decimal as string). */
    public record MarkView(String instrumentId, String price, boolean stale) {
    }

    /** A current holding, so the model can reason about the book it already has. */
    public record PortfolioLine(String bookId, String instrumentId, String quantity, String unrealizedPnl) {
    }
}
