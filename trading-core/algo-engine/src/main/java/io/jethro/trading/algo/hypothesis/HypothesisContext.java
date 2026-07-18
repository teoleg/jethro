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
                                List<PortfolioLine> portfolio, Set<String> tradableInstruments,
                                List<String> alreadyProposed, List<String> pastOutcomes,
                                String directionBalance) {

    /** Without idempotency/RAG/balance context (all default empty) — the original shape. */
    public HypothesisContext(List<MarkView> marks, List<NarrativeItem> narrative,
                             List<PortfolioLine> portfolio, Set<String> tradableInstruments) {
        this(marks, narrative, portfolio, tradableInstruments, List.of(), List.of(), "");
    }

    /** With the live calls (idempotency) but no recalled outcomes — the pre-RAG-memory shape. */
    public HypothesisContext(List<MarkView> marks, List<NarrativeItem> narrative,
                             List<PortfolioLine> portfolio, Set<String> tradableInstruments,
                             List<String> alreadyProposed) {
        this(marks, narrative, portfolio, tradableInstruments, alreadyProposed, List.of(), "");
    }

    /** With idempotency + outcome memory but no balance line — the pre-ADR-0036 shape. */
    public HypothesisContext(List<MarkView> marks, List<NarrativeItem> narrative,
                             List<PortfolioLine> portfolio, Set<String> tradableInstruments,
                             List<String> alreadyProposed, List<String> pastOutcomes) {
        this(marks, narrative, portfolio, tradableInstruments, alreadyProposed, pastOutcomes, "");
    }

    /**
     * A current price the model may reference (decimal as string), plus WHAT the instrument is —
     * asset class, currency, and a short description. Without these the model guesses an identity
     * from the ticker (e.g. calling the future "ZN" a stock named "Zapata Resources"); with them
     * it reasons about the actual instrument. {@code assetClass}/{@code description} may be null
     * when reference data is absent.
     */
    public record MarkView(String instrumentId, String assetClass, String currency,
                           String description, String price, boolean stale) {
    }

    /** A current holding, so the model can reason about the book it already has. */
    public record PortfolioLine(String bookId, String instrumentId, String quantity, String unrealizedPnl) {
    }
}
