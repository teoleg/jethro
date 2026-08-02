package io.muniworld.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A current market price for a CUSIP (ADR-0015), kept SEPARATE from the OS-sourced terms. This is what a
 * terms-only bond needs to compute live economics (current yield, YTM, duration). {@code price} is exact
 * ({@code BigDecimal}, invariant 1), {@code asOf} dates it (a trade print is a point in time), and
 * {@code source} records provenance (e.g. {@code msrb-rtrs}) — a price is never invented, only sourced.
 */
public record PriceQuote(String cusip, BigDecimal price, LocalDate asOf, String source) {
}
