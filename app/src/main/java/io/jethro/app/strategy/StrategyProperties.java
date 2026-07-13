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
        Double volReferenceBps) {

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
