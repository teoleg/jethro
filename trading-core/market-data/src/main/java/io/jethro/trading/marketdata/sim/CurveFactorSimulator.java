package io.jethro.trading.marketdata.sim;

import java.util.SplittableRandom;

/**
 * Factor-based USD SOFR curve simulator (quant-engine phase 4). Instead of walking each
 * tenor independently (which drifts into curves no market would produce), two seeded
 * factors evolve — <b>level</b> and <b>slope</b> — and every tenor's zero rate derives
 * from them (Nelson-Siegel-lite): {@code z(t) = level + slope · (1 − e^(−t/τ))}, τ = 5y.
 * The curve therefore stays coherent: parallel moves are level, steepenings are slope.
 *
 * <p>The curve shares the sim's {@link MarketRegime}: trends drift the level (a rates
 * selloff/rally the momentum strategy can genuinely catch), VOLATILE multiplies factor
 * vol, and a correlated shock round jumps the level 2–6bp like a data surprise — so the
 * rates market has episodes just as equities do, not a permanently flat drip.
 *
 * <p>Rates are emitted as sim "marks" for pseudo-instruments {@code USD.SOFR.<tenor>},
 * quoted in percent (e.g. 4.215632), scaled 1e-6 like every price; swap par rates for
 * {@link #SWAP_IDS} are quoted the same way. Deterministic: same seed and same regime
 * sequence → same curve path (ADR-0009). Calibration from these quotes into a Strata
 * curve happens downstream in risk-pnl — this class only makes market data.
 */
public final class CurveFactorSimulator implements CurveMarkSource {

    private static final double TAU_YEARS = 5.0;
    // Per-tick uniform bounds calibrated to ~4bp/day level vol, ~2bp/day slope vol at a
    // 100ms tick (≈234k ticks/trading day): daily σ / √234k, ×√3 for uniform. That puts
    // ZN's implied price vol near its configured 5% annual (D≈6.3 × 4bp ≈ 25bp/day).
    private static final double LEVEL_STEP = 1.5e-6;
    private static final double SLOPE_STEP = 0.75e-6;
    private static final double MIN_RATE = 0.0001; // 1bp floor — no negative demo rates
    // Shock round: a one-tick level jump of 2–6bp (CPI/FOMC surprise), sign from the
    // equity shock so cross-asset moves stay correlated.
    private static final double SHOCK_MIN = 0.0002;
    private static final double SHOCK_RANGE = 0.0004;
    // Idiosyncratic basis per linked future (mean-reverting yield offset): real futures
    // don't sit exactly on the curve (CTD switches, carry). Keeps the four futures from
    // being one deterministic function of two factors. Stationary σ ≈ 0.3bp of yield.
    private static final double BASIS_STEP = 0.5e-6;
    private static final double BASIS_KAPPA = 0.005;

    /** Curve-linked instruments: bond futures priced FROM the curve so rates signals on
     *  them are economically meaningful (not an independent walk). CONVENTION: price ≈
     *  base × (1 − modDuration × (Δyield(tenor) + basis)); durations ~ CTD conventions. */
    private record Linked(double tenorYears, double modDuration, double basePrice) {
    }

    // Iteration over linked futures must be in a FIXED order (basis noise draws from the
    // shared RNG) — a Map's iteration order would break seed determinism.
    private static final String[] LINKED_IDS = {"ZT", "ZF", "ZN", "ZB"};
    private static final java.util.Map<String, Linked> LINKED = java.util.Map.of(
            "ZT", new Linked(2, 1.9, 102.90),
            "ZF", new Linked(5, 4.2, 107.30),
            "ZN", new Linked(10, 6.3, 110.50),
            "ZB", new Linked(30, 17.0, 118.20));

    private final SplittableRandom random;
    private final java.util.Map<String, Double> initialZeros = new java.util.HashMap<>();
    private final java.util.Map<String, Double> basis = new java.util.HashMap<>();
    private double level;
    private double slope;
    // Stochastic TSY−SOFR swap-spread basis (fraction): mean-reverting around 0, shared by
    // all TSY tenors on top of the stylized per-node base spread — the two curves breathe
    // against each other instead of moving in lockstep. Treasury futures key off TSY.
    private double swapSpreadBasis;

    /** @param startLevel e.g. 0.038 (3.8%); @param startSlope e.g. 0.009 (long minus short). */
    public CurveFactorSimulator(long seed, double startLevel, double startSlope) {
        this.random = new SplittableRandom(seed);
        this.level = startLevel;
        this.slope = startSlope;
        for (String id : LINKED_IDS) {
            initialZeros.put(id, zeroRate(LINKED.get(id).tenorYears()));
            basis.put(id, 0.0);
        }
    }

    /** True if this instrument's price derives from the curve (Treasury futures). */
    @Override
    public boolean isLinked(String instrumentId) {
        return LINKED.containsKey(instrumentId);
    }

    /**
     * Curve-implied futures price, scaled 1e-6: base × (1 − D·(Δz(tenor) + basis)). A
     * 10bp yield rise moves ZN (D≈6.3) down ~0.63 points — bond futures trade WITH the
     * curve, plus a small mean-reverting basis of their own.
     */
    @Override
    public long linkedPriceScaled(String instrumentId) {
        Linked l = LINKED.get(instrumentId);
        // Treasury futures key off the TSY curve: SOFR zero + swap-spread basis (the constant
        // per-node spread cancels in the delta; the STOCHASTIC basis does not — futures now
        // carry genuine swap-spread risk vs the SOFR-discounted swaps).
        double deltaYield = zeroRate(l.tenorYears()) - initialZeros.get(instrumentId)
                + swapSpreadBasis + basis.get(instrumentId);
        double price = l.basePrice() * (1.0 - l.modDuration() * deltaYield);
        return Math.max(10_000L, Math.round(price * 1_000_000));
    }

    /** Advances one tick with no regime effects (CALM, no shock) — tests/back-compat. */
    public void step() {
        step(MarketRegime.CALM, 0);
    }

    /**
     * Advances the curve by EXTERNALLY supplied factor deltas (ADR-0026): the correlated
     * cross-asset simulator owns the RATES level/slope innovations so the curve — and the
     * Treasury futures and swaps priced from it — moves in concert with equities and FX.
     * Only the small mean-reverting per-future basis still evolves from this class's own
     * seeded rng (idiosyncratic by design).
     */
    @Override
    public void applyExternalStep(double dLevel, double dSlope) {
        level = Math.max(MIN_RATE, level + dLevel);
        slope += dSlope;
        stepBases();
    }

    /** Evolves the per-future bases and the shared TSY−SOFR swap-spread basis one tick. */
    private void stepBases() {
        for (String id : LINKED_IDS) {
            double b = basis.get(id);
            basis.put(id, b * (1.0 - BASIS_KAPPA) + (random.nextDouble() * 2 - 1) * BASIS_STEP);
        }
        swapSpreadBasis = swapSpreadBasis * (1.0 - BASIS_KAPPA)
                + (random.nextDouble() * 2 - 1) * BASIS_STEP;
    }

    /** US Treasury par yield for node {@code i}: SOFR zero + stylized spread + stochastic basis. */
    @Override
    public long tsyRateScaledPercent(int tenorIndex) {
        double tsy = zeroRate(TENORS[tenorIndex])
                + CurveMarkSource.TSY_SPREAD_BP[tenorIndex] * 1e-4 + swapSpreadBasis;
        return Math.round(tsy * 100 * 1_000_000);
    }

    /**
     * Advances both factors one tick under the given market regime: the regime's vol
     * multiple scales both factor steps, its drift moves the level (rates trend), and a
     * non-zero {@code shockSign} adds a one-tick 2–6bp level jump in that direction.
     */
    @Override
    public void step(MarketRegime regime, int shockSign) {
        int volMultiple = regime.volMultiple();
        level += (random.nextDouble() * 2 - 1) * LEVEL_STEP * volMultiple
                + LEVEL_STEP * regime.driftPerMille() / 1_000.0;
        slope += (random.nextDouble() * 2 - 1) * SLOPE_STEP * volMultiple;
        if (shockSign != 0) {
            level += Math.signum(shockSign) * (SHOCK_MIN + random.nextDouble() * SHOCK_RANGE);
        }
        stepBases();
    }

    /** Zero rate for a tenor in years (fraction, e.g. 0.0421). */
    public double zeroRate(double tenorYears) {
        double loading = 1.0 - Math.exp(-tenorYears / TAU_YEARS);
        return Math.max(MIN_RATE, level + slope * loading);
    }

    /** Tenor rate quoted in percent as a scaled long (1e-6 units), for the mark pipeline. */
    @Override
    public long rateScaledPercent(int tenorIndex) {
        return Math.round(zeroRate(TENORS[tenorIndex]) * 100 * 1_000_000);
    }

    /**
     * Par swap rate for {@link #SWAP_IDS}[i], quoted in percent as a scaled long — the
     * standard annual-fixed par formula on this curve's zeros:
     * {@code par = (1 − DF(n)) / Σᵢ₌₁..ₙ DF(i)}, DF(t) = e^(−z(t)·t). Downstream Strata
     * pricing (risk-pnl) computes its own par from the calibrated curve with real day
     * counts; the two agree to within a few bp, which is exactly a quote/model basis.
     */
    @Override
    public long swapParScaledPercent(int swapIndex) {
        int years = SWAP_TENOR_YEARS[swapIndex];
        double annuity = 0.0;
        for (int i = 1; i <= years; i++) {
            annuity += discountFactor(i);
        }
        double par = (1.0 - discountFactor(years)) / annuity;
        return Math.round(par * 100 * 1_000_000);
    }

    private double discountFactor(double tenorYears) {
        return Math.exp(-zeroRate(tenorYears) * tenorYears);
    }
}
