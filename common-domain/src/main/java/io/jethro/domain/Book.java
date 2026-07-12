package io.jethro.domain;

import java.util.Optional;

/**
 * A book holds positions; books form a tree via {@code parentId} for aggregation
 * (ADR-0008). Risk/PnL aggregates up the tree in the book's {@code baseCurrency}.
 */
public record Book(BookId id, String name, String baseCurrency, Optional<BookId> parentId) {

    public Book {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("book name must be non-blank");
        }
        if (baseCurrency == null || baseCurrency.isBlank()) {
            throw new IllegalArgumentException("baseCurrency must be non-blank");
        }
    }
}
