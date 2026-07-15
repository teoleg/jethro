package io.jethro.trading.algo.strategy;

import io.jethro.domain.Side;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A deterministic trade candidate from a strategy (step 8). Advisory only: it is a
 * proposal, validated by the deterministic guardrail (ADR-0018) and surfaced for a human
 * — never routed to the order path by the strategy itself (invariant 7). {@code zScore}
 * is the window move in units of the instrument's own realized volatility (a statistical
 * estimate, hence double — never money).
 */
public record TradeSignal(String instrumentId, Side side, BigDecimal referencePrice,
                          BigDecimal price, BigDecimal changeBps, double zScore, String kind) {

    /** Momentum signal (the original shape — existing call sites unchanged). */
    public TradeSignal(String instrumentId, Side side, BigDecimal referencePrice,
                       BigDecimal price, BigDecimal changeBps, double zScore) {
        this(instrumentId, side, referencePrice, price, changeBps, zScore, "momentum");
    }

    /** Human-readable why, e.g. "momentum +36bps (2.8σ) over lookback". */
    public String rationale() {
        BigDecimal bps = changeBps.setScale(0, RoundingMode.HALF_UP);
        String signed = (bps.signum() >= 0 ? "+" : "") + bps.toPlainString();
        String sigma = Double.isFinite(zScore) ? String.format("%.1fσ", Math.abs(zScore)) : "steady trend";
        if ("mean-reversion".equals(kind)) {
            return "mean-reversion — fading " + signed + "bps (" + sigma + ") over lookback";
        }
        return kind + " " + signed + "bps (" + sigma + ") over lookback";
    }
}
