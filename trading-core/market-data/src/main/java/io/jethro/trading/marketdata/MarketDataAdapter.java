package io.jethro.trading.marketdata;

/**
 * Provider SPI (ADR-0009): one adapter per provider, translating external symbology
 * and format into normalized callbacks. External symbols never cross this boundary
 * (invariant 2). Exactly one adapter session runs inside trading-core (ADR-0014).
 */
public interface MarketDataAdapter {

    /** Stable adapter name; recorded as the mark/tick source. */
    String name();

    /** Starts pushing normalized events to the listener. Idempotent start is not required; call once. */
    void start(MarketDataListener listener);

    /** Stops the feed and releases resources. Must be safe to call after start. */
    void stop();

    /** Feed connection status for the UI (ADR-0023). Default: connected, live (no data delay). */
    default FeedStatus status() {
        return new FeedStatus(name(), true, System.currentTimeMillis(), 0);
    }

    /** All feeds this adapter drives — one per source (a composite feed reports several, e.g.
     *  Finnhub + Yahoo, ADR-0024). Default: just {@link #status()}. */
    default java.util.List<FeedStatus> statuses() {
        return java.util.List.of(status());
    }
}
