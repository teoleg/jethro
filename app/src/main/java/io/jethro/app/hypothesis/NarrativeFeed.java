package io.jethro.app.hypothesis;

import io.jethro.trading.algo.hypothesis.NarrativeItem;

import java.util.List;

/**
 * The qualitative-input source for the LLM hypothesis layer (ADR-0022): news / earnings /
 * econ headlines the model reads alongside prices and the portfolio. Two implementations —
 * {@link SimNarrativeFeed} (seedable offline fixtures, local-dev default) and
 * {@link FinnhubNarrativeFeed} (real company + general market news, ADR-0024) — so a real
 * feed slots in without touching the pipeline. Nothing here is a number fed into
 * sizing/risk (invariant 1/7); the model only reads the text and a coarse sentiment tag.
 */
public interface NarrativeFeed {

    /**
     * Recent narrative items, newest last. {@code regime} lets a sim feed colour its fixtures
     * to the market state; a real feed ignores it. {@code singleNameInstruments} are the ids
     * eligible for company-specific news (equities/futures); may be empty.
     */
    List<NarrativeItem> poll(String regime, List<String> singleNameInstruments, long nowMillis);
}
