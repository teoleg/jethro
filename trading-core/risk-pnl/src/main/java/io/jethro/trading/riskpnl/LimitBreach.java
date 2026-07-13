package io.jethro.trading.riskpnl;

import java.math.BigDecimal;

/**
 * A book (or the firm, bookId {@code FIRM}) at (ALERT) or approaching (WARN) one of its
 * risk limits — a deterministic trigger for the attention floor (ADR-0017) and the basis
 * of the pre-trade guardrail (ADR-0018). {@code subject} carries the instrument for
 * concentration breaches, else empty. {@code id()} is stable per (book, metric, subject)
 * so a persisting breach updates one attention card instead of spamming.
 */
public record LimitBreach(String bookId, Metric metric, Severity severity,
                          BigDecimal actual, BigDecimal limit, String subject) {

    public enum Metric { GROSS_EXPOSURE, NET_EXPOSURE, LOSS, INSTRUMENT_EXPOSURE }

    public enum Severity { WARN, ALERT }

    public LimitBreach(String bookId, Metric metric, Severity severity, BigDecimal actual, BigDecimal limit) {
        this(bookId, metric, severity, actual, limit, "");
    }

    public String id() {
        return "risk:" + bookId + ":" + metric + (subject.isEmpty() ? "" : ":" + subject);
    }
}
