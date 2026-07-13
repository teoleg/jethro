package io.jethro.trading.marketdata.yahoo;

import java.math.BigDecimal;
import java.util.Optional;

/** A source of last-price quotes by provider symbol (ADR-0023); lets the adapter be tested
 *  without live HTTP. Implemented by {@link YahooQuoteClient}. */
public interface QuoteSource {

    /** The last price for a provider symbol, or empty if unavailable. */
    Optional<Quote> fetch(String providerSymbol);

    /** A last-price observation: exact price (invariant 1) and its source epoch-second time. */
    record Quote(BigDecimal price, long epochSeconds) {
    }
}
