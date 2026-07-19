package io.jethro.app.discovery;

/**
 * One news-outlet headline (ADR-0045/0050). Read for advisory context + universe discovery only —
 * never a number into sizing/risk, never an order (invariant 7 / ADR-0049).
 */
public record NewsItem(String id, String outlet, long timestampMillis, String title, String text, String url) {
}
