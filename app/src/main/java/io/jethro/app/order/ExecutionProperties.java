package io.jethro.app.order;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Execution cost config (ADR-0025): full bid/ask spread and fee per asset class, in basis
 * points. Dev/demo ESTIMATES of typical liquid-market costs — stated as such, never live
 * quotes. Defaults: EQUITY 5bp spread + 1bp fee; FUTURE/BOND 1 + 0.2; FX 1 + 0; SWAP 0.4bp
 * (of RATE, additive) + 0. Backtest per-fill cost derives from the same numbers so the two
 * P&L sources agree by construction.
 */
@ConfigurationProperties(prefix = "jethro.execution")
public record ExecutionProperties(Map<String, BigDecimal> spreadBps, Map<String, BigDecimal> feeBps,
                                  /** Max order notional as a fraction of the instrument's ADV
                                   *  (participation cap, ADR-0025). Default 0.02 = 2%; 0 disables. */
                                  BigDecimal maxAdvParticipation) {

    /** The participation cap, or null (= off) when explicitly configured to 0. */
    public BigDecimal maxAdvParticipationOrDefault() {
        if (maxAdvParticipation == null) {
            return new BigDecimal("0.02");
        }
        return maxAdvParticipation.signum() > 0 ? maxAdvParticipation : null;
    }

    private static final Map<String, BigDecimal> DEFAULT_SPREAD = Map.of(
            "EQUITY", new BigDecimal("5"),
            "FUTURE", new BigDecimal("1"),
            "BOND", new BigDecimal("1"),
            "FX", new BigDecimal("1"),
            "SWAP", new BigDecimal("0.4"));
    private static final Map<String, BigDecimal> DEFAULT_FEE = Map.of(
            "EQUITY", new BigDecimal("1"),
            "FUTURE", new BigDecimal("0.2"),
            "BOND", new BigDecimal("0.2"),
            "FX", BigDecimal.ZERO,
            "SWAP", BigDecimal.ZERO);

    public BigDecimal spreadFor(String assetClass) {
        return lookup(spreadBps, DEFAULT_SPREAD, assetClass);
    }

    public BigDecimal feeFor(String assetClass) {
        return lookup(feeBps, DEFAULT_FEE, assetClass);
    }

    /** One-way cost of a MARKET fill in bps of notional: half-spread + fee — what the
     *  backtest charges per fill so it measures the same economics as live sim execution. */
    public BigDecimal perFillCostBps(String assetClass) {
        return spreadFor(assetClass).divide(BigDecimal.TWO).add(feeFor(assetClass));
    }

    private static BigDecimal lookup(Map<String, BigDecimal> configured,
                                     Map<String, BigDecimal> defaults, String assetClass) {
        if (configured != null && assetClass != null && configured.containsKey(assetClass)) {
            return configured.get(assetClass);
        }
        BigDecimal fallback = assetClass != null ? defaults.get(assetClass) : null;
        return fallback != null ? fallback : defaults.get("EQUITY"); // unknown class: conservative
    }
}
