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
        /** Z-score at which a signal fires — the window move in units of the instrument's
         *  own realized vol, so each instrument self-calibrates (equities vs Treasuries). */
        Double thresholdSigmas,
        /** Floor: minimum absolute move in bps for any signal (dust-trade guard). */
        BigDecimal minSignalBps,
        BigDecimal targetNotional,
        /** Default book when an instrument's asset class has no bookByClass entry. */
        String book,
        /** Asset class → book routing, e.g. EQUITY→ALPHA, FUTURE/FX/BOND→MACRO. */
        Map<String, String> bookByClass,
        /** Auto-submit admissible signals (SIMULATED only, ADR-0019). Default false. */
        boolean autoExecute,
        /** Min seconds between auto-orders for the same instrument. */
        long autoCooldownSeconds,
        /** Hard cap on a single order's notional; signals that can't be sized under it
         *  (e.g. one ES contract > cap) are skipped, never rounded up. Default 2× target. */
        BigDecimal maxOrderNotional,
        /** Target position cap: once a book holds this much |notional| in an instrument,
         *  same-direction signals are skipped (risk-reducing ones still pass). Default 3× target. */
        BigDecimal maxPositionNotional,
        /** Reference window vol (bps) for vol-scaled sizing: order notional = target ×
         *  clamp(ref/σ, 0.5, 2) — half size in wild markets, up to double in calm. Default 15. */
        Double volReferenceBps,
        /** Per-asset-class order-notional caps (e.g. BOND=150000 so one Treasury contract
         *  is sizeable); classes not listed use maxOrderNotional. */
        Map<String, BigDecimal> maxOrderNotionalByClass,
        /** Long-only guard: when false (default), a SELL signal may only REDUCE an existing
         *  long — never open or extend a short. Set true to let the strategy take shorts. */
        Boolean allowShort,
        /** Per-position stop-loss: close a position whose unrealized return is at/below
         *  −stopLossPct (e.g. 0.008 = 0.8%). Null/≤0 disables. The risk-reducing half of
         *  the loop (ADR-0019) — without it a losing position rides forever. */
        BigDecimal stopLossPct,
        /** Per-position take-profit: close a position whose unrealized return is at/above
         *  +takeProfitPct. Null/≤0 disables. */
        BigDecimal takeProfitPct,
        /** Book de-risk backstop: when a strategy-managed book's total loss is at/over its
         *  configured max-loss cap, flatten its positions (risk-reducing orders, always
         *  admissible). Default true — this is what makes a floored book actually unwind. */
        Boolean deriskOnLossCap,
        /** Regime-aware sizing: in the sim's VOLATILE regime, scale new-entry notional by this
         *  factor (risk-off in turbulence). Default 0.5 = half size; 0 = stand aside. Exits and
         *  de-risking are unaffected — you can always reduce. */
        BigDecimal regimeVolatileScale,
        /** Vol-targeted sizing: the $ risk budget one position may carry per session day —
         *  notional = riskBudgetDaily / σ_daily (EWMA of recorded daily closes), capped at the
         *  per-class order cap. Equalizes risk across assets: a 1.8%/day single name gets less
         *  notional than a 0.4%/day Treasury future. Used whenever measured vol exists; falls
         *  back to targetNotional (+ signal-vol scaling in the strategy) until history accrues.
         *  Default 250 (≈ 25k target × 1%/day). */
        BigDecimal riskBudgetDaily,
        /** Signal algo: "momentum" (default) or "mean-reversion" — the SAME z-score detector
         *  read in opposite directions; both run through the identical guardrails, sizing and
         *  OOS harness, which is what adjudicates between them (ADR-0027). */
        String algo,
        /** Volume confirmation (ADR-0033): a signal fires only when relativeVolume ≥ this — a
         *  breakout on thin participation is discarded. Default 0.7; 0 disables. Neutral in the
         *  backtest (no volume), so it gates the live path only. */
        Double volumeConfirmMin,
        /** Liquidity-aware sizing (ADR-0033): cap an order at this fraction of the instrument's
         *  live measured ADV — below the execution participation cap so a sized order passes.
         *  Default 0.015; 0 disables. */
        Double liquidityCapAdvFraction) {

    public String algoOrDefault() {
        return algo != null && !algo.isBlank() ? algo.trim().toLowerCase() : "momentum";
    }

    public double volumeConfirmMinOrDefault() {
        return volumeConfirmMin != null && volumeConfirmMin >= 0 ? volumeConfirmMin : 0.7;
    }

    public double liquidityCapAdvFractionOrDefault() {
        return liquidityCapAdvFraction != null && liquidityCapAdvFraction > 0 ? liquidityCapAdvFraction : 0.015;
    }

    public BigDecimal riskBudgetDailyOrDefault() {
        return riskBudgetDaily != null && riskBudgetDaily.signum() > 0
                ? riskBudgetDaily : new BigDecimal("250");
    }

    public BigDecimal regimeVolatileScaleOrDefault() {
        return regimeVolatileScale != null && regimeVolatileScale.signum() >= 0
                ? regimeVolatileScale : new BigDecimal("0.5");
    }

    /** The new-entry sizing scale for a market regime — reduced in the high-vol regimes
     *  (VOLATILE, and ADR-0026's RISK_OFF / INFLATION_SHOCK), 1 otherwise. */
    public BigDecimal regimeScaleFor(String regime) {
        return switch (regime) {
            case "VOLATILE", "RISK_OFF", "INFLATION_SHOCK" -> regimeVolatileScaleOrDefault();
            default -> BigDecimal.ONE;
        };
    }

    public boolean allowShortOrDefault() {
        return allowShort != null && allowShort;
    }

    public boolean deriskOnLossCapOrDefault() {
        return deriskOnLossCap == null || deriskOnLossCap;
    }

    /** Stop-loss as a positive fraction, or null when disabled. */
    public BigDecimal stopLossPctOrNull() {
        return stopLossPct != null && stopLossPct.signum() > 0 ? stopLossPct : null;
    }

    /** Take-profit as a positive fraction, or null when disabled. */
    public BigDecimal takeProfitPctOrNull() {
        return takeProfitPct != null && takeProfitPct.signum() > 0 ? takeProfitPct : null;
    }

    public double thresholdSigmasOrDefault() {
        return thresholdSigmas != null ? thresholdSigmas : 2.5;
    }

    public BigDecimal minSignalBpsOrDefault() {
        return minSignalBps != null ? minSignalBps : new BigDecimal("2");
    }

    public double volReferenceBpsOrDefault() {
        return volReferenceBps != null ? volReferenceBps : 15.0;
    }

    /** The per-instrument position cap, defaulting to 3× the target notional. */
    public BigDecimal maxPositionNotionalOrDefault() {
        return maxPositionNotional != null ? maxPositionNotional
                : targetNotional.multiply(new BigDecimal("3"));
    }

    /** The single-order notional cap, defaulting to 2× the target notional. */
    public BigDecimal maxOrderNotionalOrDefault() {
        return maxOrderNotional != null ? maxOrderNotional
                : targetNotional.multiply(new BigDecimal("2"));
    }

    /** Order-notional cap for an asset class: class override else the default. */
    public BigDecimal maxOrderNotionalFor(String assetClass) {
        if (assetClass != null && maxOrderNotionalByClass != null) {
            BigDecimal override = maxOrderNotionalByClass.get(assetClass);
            if (override != null) {
                return override;
            }
        }
        return maxOrderNotionalOrDefault();
    }

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
