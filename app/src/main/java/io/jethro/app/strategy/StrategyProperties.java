package io.jethro.app.strategy;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Toy-strategy config (jethro.strategy). The strategy proposes; suggestions are sized to
 * {@code targetNotional}, routed to the book that fits the instrument's asset class
 * ({@code bookByClass}, falling back to {@code book}), checked by the guardrail, and
 * surfaced on the attention feed — never routed automatically (invariant 7 / ADR-0018).
 */
@ConfigurationProperties(prefix = "jethro.strategy")
public record StrategyProperties(
        boolean enabled,
        long intervalSeconds,
        int lookback,
        BigDecimal thresholdBps,
        BigDecimal targetNotional,
        /** Default book when an instrument's asset class has no bookByClass entry. */
        String book,
        /** Asset class → book routing, e.g. EQUITY→ALPHA, FUTURE/FX/BOND→MACRO. */
        Map<String, String> bookByClass,
        /** Auto-submit admissible signals (SIMULATED only, ADR-0019). Default false. */
        boolean autoExecute,
        /** Min seconds between auto-orders for the same instrument. */
        long autoCooldownSeconds) {

    /** Resolves the target book for an instrument's asset class, or the default. */
    public String bookFor(String assetClass) {
        if (assetClass != null && bookByClass != null) {
            String routed = bookByClass.get(assetClass);
            if (routed != null) {
                return routed;
            }
        }
        return book;
    }
}
