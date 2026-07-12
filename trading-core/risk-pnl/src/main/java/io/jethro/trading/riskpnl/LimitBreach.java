package io.jethro.trading.riskpnl;

import java.math.BigDecimal;

/**
 * A book that is at (ALERT) or approaching (WARN) one of its risk limits — a
 * deterministic trigger for the attention floor (ADR-0017) and the basis of the
 * pre-trade guardrail (ADR-0018). {@code id()} is stable per (book, metric) so a
 * persisting breach updates one attention card instead of spamming.
 */
public record LimitBreach(String bookId, Metric metric, Severity severity,
                          BigDecimal actual, BigDecimal limit) {

    public enum Metric { GROSS_EXPOSURE, NET_EXPOSURE, LOSS }

    public enum Severity { WARN, ALERT }

    public String id() {
        return "risk:" + bookId + ":" + metric;
    }
}
