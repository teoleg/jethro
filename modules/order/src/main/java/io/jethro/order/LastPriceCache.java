package io.jethro.order;

import io.jethro.domain.InstrumentId;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Last mark per instrument, fed from the md.marks topic — the order module's own view
 * of prices for pricing simulated fills. Independent of trading-core's in-process
 * MarkCache (cross-module data flows via topics, ADR-0015).
 */
public final class LastPriceCache {

    private final ConcurrentHashMap<String, BigDecimal> prices = new ConcurrentHashMap<>();

    public void update(String instrumentId, BigDecimal price) {
        prices.put(instrumentId, price);
    }

    public Optional<BigDecimal> lastPrice(InstrumentId instrumentId) {
        return Optional.ofNullable(prices.get(instrumentId.value()));
    }
}
