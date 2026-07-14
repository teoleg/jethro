package io.jethro.trading.marketdata;

/**
 * Hot-path callback from a feed adapter (ADR-0009/0014). Primitive scaled-long money
 * per invariant 1 and the hot-path conventions: no BigDecimal, no boxing. The
 * instrumentId reference is a per-instrument constant owned by the adapter — no
 * per-tick allocation.
 */
public interface MarketDataListener {

    /**
     * A trade print. {@code priceScaled}/{@code qtyScaled} carry
     * {@link io.jethro.domain.Decimals#PRICE_SCALE}/{@link io.jethro.domain.Decimals#QTY_SCALE}.
     * Dual timestamps per invariant 4.
     */
    void onTrade(String instrumentId, long priceScaled, long qtyScaled,
                 long providerTimestampMillis, long ingestTimestampMillis);

    /**
     * A top-of-book quote (ADR-0025): best bid/ask, same scaled-long conventions as
     * {@link #onTrade}. Default no-op — adapters without quote data (and listeners that
     * only care about trades) need no change.
     */
    default void onQuote(String instrumentId, long bidScaled, long askScaled,
                         long providerTimestampMillis, long ingestTimestampMillis) {
    }
}
