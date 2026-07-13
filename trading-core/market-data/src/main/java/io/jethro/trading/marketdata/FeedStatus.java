package io.jethro.trading.marketdata;

/**
 * Connection status of a market-data feed, for the UI's per-feed indicators (ADR-0023).
 *
 * @param provider          adapter name (e.g. "sim", "yahoo").
 * @param connected         true if the feed is currently delivering data.
 * @param lastUpdateMillis  wall-clock time of the last successful update (ingest), or 0 if none.
 * @param dataDelaySeconds  how stale the DATA is at source (0 for the live sim; ~900 for a
 *                          15-min-delayed provider) — distinct from connection freshness.
 */
public record FeedStatus(String provider, boolean connected, long lastUpdateMillis, long dataDelaySeconds) {
}
