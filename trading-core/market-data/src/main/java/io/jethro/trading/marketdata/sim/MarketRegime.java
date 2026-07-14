package io.jethro.trading.marketdata.sim;

/**
 * Market regime for the sim (episodes lasting minutes, correlated across instruments).
 * Drift and vol are expressed relative to each instrument's own calibrated step, so a
 * regime means the same thing for AAPL and a Treasury future.
 *
 * <ul>
 *   <li>{@code driftPerMille} — signed drift per tick, in thousandths of the instrument's
 *       max step (≈30 gives a ~2σ two-minute trend: catchable momentum, not a teleport).</li>
 *   <li>{@code volMultiple} — multiplies the random step (VOLATILE ≈ a vol spike).</li>
 * </ul>
 */
public enum MarketRegime {
    CALM(0, 1),
    TREND_UP(30, 1),
    TREND_DOWN(-30, 1),
    VOLATILE(0, 3),
    /** Flight to quality (ADR-0026): equities down, yields down (bond futures up), USD bid. */
    RISK_OFF(-60, 3),
    /** 2022-style shock (ADR-0026): equities down WHILE yields up — stock-bond corr flips. */
    INFLATION_SHOCK(-40, 2);

    private final int driftPerMille;
    private final int volMultiple;

    MarketRegime(int driftPerMille, int volMultiple) {
        this.driftPerMille = driftPerMille;
        this.volMultiple = volMultiple;
    }

    int driftPerMille() {
        return driftPerMille;
    }

    int volMultiple() {
        return volMultiple;
    }
}
