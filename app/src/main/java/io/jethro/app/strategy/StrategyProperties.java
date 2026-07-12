package io.jethro.app.strategy;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Toy-strategy config (jethro.strategy). The strategy proposes; suggestions are sized to
 * {@code targetNotional}, checked by the guardrail against {@code book}, and surfaced on
 * the attention feed — never routed automatically (invariant 7 / ADR-0018).
 */
@ConfigurationProperties(prefix = "jethro.strategy")
public record StrategyProperties(
        boolean enabled,
        long intervalSeconds,
        int lookback,
        BigDecimal thresholdBps,
        BigDecimal targetNotional,
        String book,
        /** Auto-submit admissible signals (SIMULATED only, ADR-0019). Default false. */
        boolean autoExecute,
        /** Min seconds between auto-orders for the same instrument. */
        long autoCooldownSeconds) {
}
