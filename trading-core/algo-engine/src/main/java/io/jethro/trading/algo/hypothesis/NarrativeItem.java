package io.jethro.trading.algo.hypothesis;

/**
 * One qualitative input to the LLM hypothesis layer (ADR-0022): a headline, econ print, or
 * earnings note. The shape is provider-agnostic: it comes from real Finnhub news when a key is
 * set (ADR-0024) or a seedable offline sim feed otherwise (ADR-0009 spirit) — the pipeline is
 * identical either way.
 *
 * @param instrumentId the instrument it concerns, or null for a market-wide macro item.
 * @param sentiment    a coarse ordinal tag — never a number fed into sizing/risk.
 */
public record NarrativeItem(String id, long timestampMillis, Category category,
                            String instrumentId, Sentiment sentiment, String headline) {

    public enum Category { MACRO, EARNINGS, NEWS }

    public enum Sentiment { BULLISH, BEARISH, NEUTRAL }
}
