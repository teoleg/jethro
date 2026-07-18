package io.jethro.trading.algo.strategy;

import io.jethro.domain.Side;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A deterministic trade candidate from a strategy (step 8). Advisory only: it is a
 * proposal, validated by the deterministic guardrail (ADR-0018) and surfaced for a human
 * — never routed to the order path by the strategy itself (invariant 7).
 *
 * <p>Two volatility readings (both statistical estimates, hence double — never money):
 * {@code zScore} is the move in units of the <b>window's own realized</b> vol — the
 * firing/sizing statistic, self-inflated by the very move it measures. {@code
 * baselineSigmas} is the same move in units of the instrument's <b>slow EWMA baseline</b>
 * vol (vol as it was <i>before</i> this move), so a news gap reads as the ~15–20σ event it
 * is rather than a tame in-window number. The baseline reading is for the human/log; it
 * does not change firing (which stays on {@code zScore} to preserve the OOS calibration).
 */
public record TradeSignal(String instrumentId, Side side, BigDecimal referencePrice,
                          BigDecimal price, BigDecimal changeBps, double zScore,
                          double baselineSigmas, String kind) {

    /** Momentum signal, baseline reading supplied. */
    public TradeSignal(String instrumentId, Side side, BigDecimal referencePrice,
                       BigDecimal price, BigDecimal changeBps, double zScore, double baselineSigmas) {
        this(instrumentId, side, referencePrice, price, changeBps, zScore, baselineSigmas, "momentum");
    }

    /** Original shape (baseline defaults to the window z — existing call sites unchanged). */
    public TradeSignal(String instrumentId, Side side, BigDecimal referencePrice,
                       BigDecimal price, BigDecimal changeBps, double zScore) {
        this(instrumentId, side, referencePrice, price, changeBps, zScore, zScore, "momentum");
    }

    /**
     * Human-readable why, leading with the honest baseline reading, e.g.
     * "momentum +443bps, 19σ vs normal (2.8σ in-window) over lookback".
     */
    public String rationale() {
        BigDecimal bps = changeBps.setScale(0, RoundingMode.HALF_UP);
        String signed = (bps.signum() >= 0 ? "+" : "") + bps.toPlainString();
        String head = Double.isFinite(baselineSigmas)
                ? String.format("%.0fσ vs normal", Math.abs(baselineSigmas)) : "steady trend";
        String inWin = Double.isFinite(zScore) ? String.format("%.1fσ in-window", Math.abs(zScore)) : "steady";
        String tail = signed + "bps, " + head + " (" + inWin + ") over lookback";
        return "mean-reversion".equals(kind) ? "mean-reversion — fading " + tail : kind + " " + tail;
    }
}
