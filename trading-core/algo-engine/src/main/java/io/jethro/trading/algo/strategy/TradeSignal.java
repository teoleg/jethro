package io.jethro.trading.algo.strategy;

import io.jethro.domain.Side;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A deterministic trade candidate from a strategy (step 8). Advisory only: it is a
 * proposal, validated by the deterministic guardrail (ADR-0018) and surfaced for a human
 * — never routed to the order path by the strategy itself (invariant 7).
 */
public record TradeSignal(String instrumentId, Side side, BigDecimal referencePrice,
                          BigDecimal price, BigDecimal changeBps) {

    /** Human-readable why, e.g. "momentum +62bps over lookback". */
    public String rationale() {
        BigDecimal bps = changeBps.setScale(0, RoundingMode.HALF_UP);
        return "momentum " + (bps.signum() >= 0 ? "+" : "") + bps.toPlainString() + "bps over lookback";
    }
}
