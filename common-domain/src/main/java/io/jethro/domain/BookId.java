package io.jethro.domain;

/** Identifier for a book (ADR-0008). */
public record BookId(String value) {
    public BookId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("bookId must be non-blank");
        }
    }
}
