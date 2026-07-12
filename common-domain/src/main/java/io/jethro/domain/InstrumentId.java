package io.jethro.domain;

/**
 * Internal stable surrogate identifier for an instrument (ADR-0008). External provider
 * symbology never leaves the market-data module (invariant 2); everything internal
 * keys on this type.
 */
public record InstrumentId(String value) {
    public InstrumentId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("instrumentId must be non-blank");
        }
    }
}
