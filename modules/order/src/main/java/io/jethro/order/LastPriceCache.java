package io.jethro.order;

import io.jethro.domain.InstrumentId;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Last mark (+ top-of-book quote when the feed carries one, ADR-0025) per instrument, fed
 * from the md.marks topic — the order module's own view of prices for pricing simulated
 * fills. Independent of trading-core's in-process MarkCache (cross-module data flows via
 * topics, ADR-0015).
 */
public final class LastPriceCache {

    /** The mark and its quote; {@code bid}/{@code ask} null when the feed has no quote data. */
    public record Quote(BigDecimal mid, BigDecimal bid, BigDecimal ask) {
    }

    private final ConcurrentHashMap<String, Quote> prices = new ConcurrentHashMap<>();

    public void update(String instrumentId, BigDecimal price) {
        update(instrumentId, price, null, null);
    }

    public void update(String instrumentId, BigDecimal price, BigDecimal bid, BigDecimal ask) {
        prices.put(instrumentId, new Quote(price, bid, ask));
    }

    public Optional<BigDecimal> lastPrice(InstrumentId instrumentId) {
        return quote(instrumentId).map(Quote::mid);
    }

    public Optional<Quote> quote(InstrumentId instrumentId) {
        return Optional.ofNullable(prices.get(instrumentId.value()));
    }
}
