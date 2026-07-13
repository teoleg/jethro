package io.jethro.trading.algo.hypothesis;

/**
 * One qualitative input to the LLM hypothesis layer (ADR-0022): a headline, econ print, or
 * earnings note. Locally these come from a seedable sim feed (no real news source yet,
 * ADR-0009 spirit); the shape is provider-agnostic so a real feed slots in unchanged.
 *
 * @param instrumentId the instrument it concerns, or null for a market-wide macro item.
 * @param sentiment    a coarse ordinal tag — never a number fed into sizing/risk.
 */
public record NarrativeItem(String id, long timestampMillis, Category category,
                            String instrumentId, Sentiment sentiment, String headline) {

    public enum Category { MACRO, EARNINGS, NEWS }

    public enum Sentiment { BULLISH, BEARISH, NEUTRAL }
}
