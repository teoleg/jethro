package io.jethro.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Latest valuation price for an instrument (ADR-0008). Dual timestamps per
 * invariant 4 — staleness must always be measurable; marks restored from the
 * warm cache are stale until the feed refreshes them.
 */
public record Mark(
        InstrumentId instrumentId,
        BigDecimal price,
        String source,
        Instant providerTimestamp,
        Instant ingestTimestamp) {

    public Mark {
        if (price == null || price.signum() < 0) {
            throw new IllegalArgumentException("mark price must be non-negative");
        }
    }
}
