package io.jethro.trading.marketdata.sim;

import java.util.SplittableRandom;

/**
 * Factor-based USD SOFR curve simulator (quant-engine phase 4). Instead of walking each
 * tenor independently (which drifts into curves no market would produce), two seeded
 * factors evolve — <b>level</b> and <b>slope</b> — and every tenor's zero rate derives
 * from them (Nelson-Siegel-lite): {@code z(t) = level + slope · (1 − e^(−t/τ))}, τ = 5y.
 * The curve therefore stays coherent: parallel moves are level, steepenings are slope.
 *
 * <p>Rates are emitted as sim "marks" for pseudo-instruments {@code USD.SOFR.<tenor>},
 * quoted in percent (e.g. 4.215632), scaled 1e-6 like every price. Deterministic:
 * same seed → same curve path (ADR-0009). Calibration from these quotes into a Strata
 * curve happens downstream in risk-pnl — this class only makes market data.
 */
public final class CurveFactorSimulator {

    /** Tenor grid in years and the pseudo-instrument ids the rates are published under. */
    public static final double[] TENORS = {1, 2, 5, 10, 30};
    public static final String[] TENOR_IDS = {
            "USD.SOFR.1Y", "USD.SOFR.2Y", "USD.SOFR.5Y", "USD.SOFR.10Y", "USD.SOFR.30Y"};

    private static final double TAU_YEARS = 5.0;
    // Per-tick uniform bounds calibrated to ~3bp/day level vol, ~1.5bp/day slope vol at
    // a 100ms tick (≈234k ticks/trading day): daily σ / √234k, ×√3 for uniform.
    private static final double LEVEL_STEP = 1.1e-6;
    private static final double SLOPE_STEP = 0.55e-6;
    private static final double MIN_RATE = 0.0001; // 1bp floor — no negative demo rates

    /** Curve-linked instruments: bond futures priced FROM the curve so rates signals on
     *  them are economically meaningful (not an independent walk). CONVENTION: price ≈
     *  base × (1 − modDuration × Δyield(tenor)); durations ~ CTD conventions. */
    private record Linked(double tenorYears, double modDuration, double basePrice) {
    }

    private static final java.util.Map<String, Linked> LINKED = java.util.Map.of(
            "ZT", new Linked(2, 1.9, 102.90),
            "ZF", new Linked(5, 4.2, 107.30),
            "ZN", new Linked(10, 6.3, 110.50),
            "ZB", new Linked(30, 17.0, 118.20));

    private final SplittableRandom random;
    private final java.util.Map<String, Double> initialZeros = new java.util.HashMap<>();
    private double level;
    private double slope;

    /** @param startLevel e.g. 0.038 (3.8%); @param startSlope e.g. 0.009 (long minus short). */
    public CurveFactorSimulator(long seed, double startLevel, double startSlope) {
        this.random = new SplittableRandom(seed);
        this.level = startLevel;
        this.slope = startSlope;
        LINKED.forEach((id, l) -> initialZeros.put(id, zeroRate(l.tenorYears())));
    }

    /** True if this instrument's price derives from the curve (Treasury futures). */
    public boolean isLinked(String instrumentId) {
        return LINKED.containsKey(instrumentId);
    }

    /**
     * Curve-implied futures price, scaled 1e-6: base × (1 − D·Δz(tenor)). A 10bp yield
     * rise moves ZN (D≈6.3) down ~0.63 points — bond futures now trade WITH the curve.
     */
    public long linkedPriceScaled(String instrumentId) {
        Linked l = LINKED.get(instrumentId);
        double deltaYield = zeroRate(l.tenorYears()) - initialZeros.get(instrumentId);
        double price = l.basePrice() * (1.0 - l.modDuration() * deltaYield);
        return Math.max(10_000L, Math.round(price * 1_000_000));
    }

    /** Advances both factors one tick. */
    public void step() {
        level += (random.nextDouble() * 2 - 1) * LEVEL_STEP;
        slope += (random.nextDouble() * 2 - 1) * SLOPE_STEP;
    }

    /** Zero rate for a tenor in years (fraction, e.g. 0.0421). */
    public double zeroRate(double tenorYears) {
        double loading = 1.0 - Math.exp(-tenorYears / TAU_YEARS);
        return Math.max(MIN_RATE, level + slope * loading);
    }

    /** Tenor rate quoted in percent as a scaled long (1e-6 units), for the mark pipeline. */
    public long rateScaledPercent(int tenorIndex) {
        return Math.round(zeroRate(TENORS[tenorIndex]) * 100 * 1_000_000);
    }
}
