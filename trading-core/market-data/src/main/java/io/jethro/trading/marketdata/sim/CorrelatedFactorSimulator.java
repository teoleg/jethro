package io.jethro.trading.marketdata.sim;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.SplittableRandom;

/**
 * Cross-asset factor simulator (ADR-0026): every tick draws ONE correlated innovation vector
 * over the global factors [EQUITY, RATES_LEVEL, RATES_SLOPE, USD] from the current regime's
 * correlation matrix (Cholesky), with <b>multivariate Student-t</b> innovations (fat tails
 * that hit all markets together — how real tail days behave). Instrument log-returns are
 * {@code rᵢ = βᵢ,eq·f_eq + βᵢ,usd·f_usd + idioᵢ}; the RATES factor deltas are exported for
 * the curve simulator so Treasury futures and swaps (priced FROM the curve) move in concert
 * with equities and FX. Regimes follow a seeded Markov chain with per-regime drift, vol
 * multiple and correlations — RISK_OFF couples equities↓ / yields↓ / USD↑, INFLATION_SHOCK
 * couples equities↓ / yields↑ (the 2022 pattern).
 *
 * <p><b>Time compression:</b> one simulated trading "day" elapses every
 * {@code simSecondsPerDay} wall seconds (default 120), so multi-day regimes play out in
 * minutes. All calibrated per-day/per-year quantities are scaled by
 * {@code dtDays = tickSeconds / simSecondsPerDay}.
 *
 * <p>Deterministic (ADR-0009): a single seeded {@link SplittableRandom} drives everything —
 * same seed + same config ⇒ the same multi-asset tape, exactly.
 */
public final class CorrelatedFactorSimulator {

    private static final Logger log = LoggerFactory.getLogger(CorrelatedFactorSimulator.class);

    private static final int F_EQ = 0;
    private static final int F_LVL = 1;
    private static final int F_SLP = 2;
    private static final int F_USD = 3;
    private static final int FACTORS = 4;
    private static final double TRADING_DAYS_PER_YEAR = 252.0;
    /** Default hard price band: a price stays within [start/4, start·4] for the session. */
    public static final double DEFAULT_PRICE_BAND = 4.0;
    private static final long MIN_PRICE_SCALED = 10_000L; // 0.01 — no zero/negative prices

    private SplittableRandom random;          // non-final: a live reseed (ADR-0031) swaps it
    private final FactorModelConfig cfg;
    private final double dtDays;
    private final double sqrtDtDays;
    private final SimControl control;         // live dials (ADR-0031); identity-valued by default
    private final long[] startPricesScaled;   // kept for a live reseed → reset to the seeded start

    // Per-regime precomputed state, indexed like cfg.regimes()
    private final double[][][] cholesky;      // [regime][][]
    private final double[][] transitionTick;  // per-tick transition matrix

    // Per-instrument state, aligned to the ids the adapter asked for
    private final String[] ids;
    private final double[] betaEq;
    private final double[] betaUsd;
    private final double[] idioSigmaTick;     // per-tick idio vol (regime multiple applied later)
    private final double[] price;             // evolving price (double internally; long at the boundary)
    private final double[] bandFloor;         // hard session band (reflecting) — see bound()
    private final double[] bandCap;
    private final double[] lastAbsReturn;     // |return| this tick — volume clusters with it
    private final double[] emaAbsReturn;      // slow baseline of |return|; the clustering normalizer

    private int regimeIndex;
    private int shockSign;                    // -1 on a shock-entry tick, else 0
    private double lastLevelDelta;            // fraction (e.g. -0.0004 = -4bp), consumed by the curve
    private double lastSlopeDelta;

    public CorrelatedFactorSimulator(long seed, FactorModelConfig cfg, List<String> instrumentIds,
                                     long[] startPricesScaled, double tickSeconds, double simSecondsPerDay) {
        this(seed, cfg, instrumentIds, startPricesScaled, tickSeconds, simSecondsPerDay,
                new SimControl(1, instrumentIds));
    }

    /** With a live {@link SimControl} (ADR-0031): the panel's dials are read each tick. A
     *  control left at its defaults produces the exact same seeded tape as the constructor above. */
    public CorrelatedFactorSimulator(long seed, FactorModelConfig cfg, List<String> instrumentIds,
                                     long[] startPricesScaled, double tickSeconds, double simSecondsPerDay,
                                     SimControl control) {
        this(seed, cfg, instrumentIds, startPricesScaled, tickSeconds, simSecondsPerDay, control,
                DEFAULT_PRICE_BAND);
    }

    /**
     * @param priceBandMultiple hard band on every price as a multiple of its session anchor
     *        (start price): price stays within [start/B, start·B], enforced by REFLECTING the
     *        overshoot back inside (tape stays continuous, never a wall-pin). A maxed drift fader
     *        or stacked nudges therefore cannot run a price to absurdity. ≤ 1 disables.
     */
    public CorrelatedFactorSimulator(long seed, FactorModelConfig cfg, List<String> instrumentIds,
                                     long[] startPricesScaled, double tickSeconds, double simSecondsPerDay,
                                     SimControl control, double priceBandMultiple) {
        if (instrumentIds.size() != startPricesScaled.length) {
            throw new IllegalArgumentException("start prices must align with instruments");
        }
        this.random = new SplittableRandom(seed);
        this.control = control;
        this.startPricesScaled = startPricesScaled.clone();
        this.cfg = cfg;
        this.dtDays = tickSeconds / simSecondsPerDay;
        this.sqrtDtDays = Math.sqrt(dtDays);

        // Cholesky per regime — a bad correlation matrix fails FAST at startup, never mid-run.
        this.cholesky = new double[cfg.regimes().size()][][];
        for (int r = 0; r < cfg.regimes().size(); r++) {
            cholesky[r] = choleskyWithJitter(cfg.regimes().get(r).factorCorrelation(),
                    cfg.regimes().get(r).name());
        }
        this.transitionTick = perTickTransition(cfg.transitionPerDay(), dtDays);

        int n = instrumentIds.size();
        this.ids = instrumentIds.toArray(String[]::new);
        this.betaEq = new double[n];
        this.betaUsd = new double[n];
        this.idioSigmaTick = new double[n];
        this.price = new double[n];
        double rhoEqUsdCalm = baseEqUsdCorrelation();
        this.bandFloor = new double[n];
        this.bandCap = new double[n];
        this.lastAbsReturn = new double[n];
        this.emaAbsReturn = new double[n];
        boolean banded = priceBandMultiple > 1.0;
        for (int i = 0; i < n; i++) {
            FactorModelConfig.InstrumentSpec spec = specFor(ids[i]);
            betaEq[i] = spec.betaEquity();
            betaUsd[i] = spec.betaUsd();
            idioSigmaTick[i] = idioSigmaAnnual(spec, rhoEqUsdCalm) / Math.sqrt(TRADING_DAYS_PER_YEAR) * sqrtDtDays
                    / 1.0; // per-tick, in return units
            price[i] = startPricesScaled[i] / 1_000_000.0;
            bandFloor[i] = banded ? price[i] / priceBandMultiple : 0.0;
            bandCap[i] = banded ? price[i] * priceBandMultiple : Double.MAX_VALUE;
            // Seed the volume-clustering baseline at the name's typical per-tick move, so the
            // first ticks aren't mis-scaled before the EWMA warms up.
            lastAbsReturn[i] = idioSigmaTick[i];
            emaAbsReturn[i] = idioSigmaTick[i];
        }
        this.regimeIndex = Math.max(0, cfg.regimeIndex("CALM"));
    }

    /**
     * Enforces the session price band by reflection: an overshoot beyond the cap/floor folds back
     * inside by the same distance in log space ({@code p → cap²/p}), so the tape stays continuous
     * and mean-reverts off the wall instead of pinning to it. Deterministic — a pure function of
     * the already-drawn price. A pathological multi-band overshoot clamps to the boundary.
     */
    private void bound(int i) {
        double p = price[i];
        if (p > bandCap[i]) {
            p = bandCap[i] * bandCap[i] / p;
            price[i] = Math.max(p, bandFloor[i]);
        } else if (p < bandFloor[i] && bandFloor[i] > 0) {
            p = bandFloor[i] * bandFloor[i] / p;
            price[i] = Math.min(p, bandCap[i]);
        }
    }

    /** Advances one tick: regime transition, correlated factor draw, per-instrument returns.
     *  First applies any pending live-control actions (ADR-0031): a reseed, one-shot price
     *  nudges, and a forced regime; all default to no-ops so the seeded tape is unchanged. */
    public void nextTick() {
        shockSign = 0;
        applyControlActions();
        MarketRegime forced = control.regimeOverride();
        if (forced != null) {
            int idx = cfg.regimeIndex(forced.name());
            if (idx >= 0) {
                regimeIndex = idx;      // hold the forced regime; skip the Markov draw
            } else {
                maybeTransitionRegime(); // forced regime isn't configured — fall back to AUTO
            }
        } else {
            maybeTransitionRegime();
        }
        step(dtDays, sqrtDtDays);
    }

    /** Drains the control's one-shot actions before the tick evolves: reseed (fresh RNG +
     *  reset to the seeded start prices) then per-instrument price nudges. Cheap and a no-op
     *  when the panel is untouched. */
    private void applyControlActions() {
        long reseed = control.consumeReseed();
        if (reseed != SimControl.NO_RESEED) {
            random = new SplittableRandom(reseed);
            for (int i = 0; i < ids.length; i++) {
                price[i] = startPricesScaled[i] / 1_000_000.0;
            }
        }
        for (int i = 0; i < ids.length; i++) {
            double nudge = control.consumeNudge(i);
            if (nudge != 0.0) {
                price[i] = Math.max(MIN_PRICE_SCALED / 1_000_000.0, price[i] * (1.0 + nudge));
                bound(i);
            }
        }
    }

    /**
     * The close→open move at a simulated day boundary (ADR-0026/0027): ONE correlated draw
     * carrying {@code overnightDayFraction} of a trading day's variance (≈0.3 is the stylized
     * US-equity close-to-open share), applied as a single gap between consecutive ticks — news
     * that lands while the session is closed reprices at the open, it doesn't drift in. Uses
     * the same regime/Cholesky/Student-t machinery as {@link #nextTick}, so overnight gaps
     * stay cross-asset coherent; the rates deltas are exported for the curve as usual.
     */
    public void overnightGap(double overnightDayFraction) {
        shockSign = 0;
        step(overnightDayFraction, Math.sqrt(overnightDayFraction));
    }

    /** One correlated increment over {@code dDays} trading days; idio vol rescales with time. */
    private void step(double dDays, double sqrtDDays) {
        FactorModelConfig.RegimeSpec regime = cfg.regimes().get(regimeIndex);
        double volMult = regime.volMultiple();
        // Idio per-tick vols were precomputed for dtDays; rescale to this increment's horizon.
        double idioTimeScale = sqrtDDays / sqrtDtDays;

        // Correlated standard-normal vector via Cholesky, then the shared Student-t scale:
        // chi² with ν dof over all factors AND idios, so a fat-tail day is fat EVERYWHERE
        // (multivariate t — the joint tails real cross-asset crashes have).
        double[] z = new double[FACTORS];
        for (int k = 0; k < FACTORS; k++) {
            z[k] = random.nextGaussian();
        }
        double[] x = multiply(cholesky[regimeIndex], z);
        double tScale = studentTScale();

        double fEq = regime.equityDriftAnnual() * dDays / TRADING_DAYS_PER_YEAR
                + cfg.equityFactorVolAnnual() / Math.sqrt(TRADING_DAYS_PER_YEAR) * sqrtDDays * volMult * tScale * x[F_EQ];
        double fUsd = regime.usdDriftAnnual() * dDays / TRADING_DAYS_PER_YEAR
                + cfg.usdFactorVolAnnual() / Math.sqrt(TRADING_DAYS_PER_YEAR) * sqrtDDays * volMult * tScale * x[F_USD];
        lastLevelDelta = regime.ratesDriftBpPerDay() * 1e-4 * dDays
                + cfg.ratesLevelVolBpPerDay() * 1e-4 * sqrtDDays * volMult * tScale * x[F_LVL];
        lastSlopeDelta = cfg.ratesSlopeVolBpPerDay() * 1e-4 * sqrtDDays * volMult * tScale * x[F_SLP];

        for (int i = 0; i < ids.length; i++) {
            // Live per-instrument dials (ADR-0031): volMultiplier scales this name's idiosyncratic
            // vol, driftBias adds a per-tick log-drift. Both default to the identity (×1, +0), so an
            // untouched panel leaves the return — and the RNG stream — bit-for-bit unchanged.
            double ctlVol = control.volMultiplier(i);
            double idio = idioSigmaTick[i] * idioTimeScale * volMult * ctlVol * tScale * random.nextGaussian();
            double r = betaEq[i] * fEq + betaUsd[i] * fUsd + idio + control.driftBias(i);
            price[i] = price[i] * Math.exp(r);
            bound(i);
            recordReturnForVolume(i, r);
        }
    }

    /** Tracks this tick's move magnitude so volume can cluster with it: a big-move tick prints
     *  heavier than the name's recent normal (volume follows volatility), and the slow EWMA is
     *  the normalizer that keeps the effect relative per instrument, not absolute. */
    private void recordReturnForVolume(int i, double r) {
        double absR = Math.abs(r);
        lastAbsReturn[i] = absR;
        emaAbsReturn[i] = (1.0 - VOL_EMA_ALPHA) * emaAbsReturn[i] + VOL_EMA_ALPHA * absR;
    }

    private static final double VOL_EMA_ALPHA = 0.02;   // slow |return| baseline (the normalizer)
    private static final double VOL_CLUSTER_CAP = 8.0;  // one tick prints at most 8× its name's normal
    private static final double VOL_CLUSTER_FLOOR = 0.25;

    /** Regime switch check; entering a shock regime applies a one-tick correlated gap. */
    private void maybeTransitionRegime() {
        double u = random.nextDouble();
        double acc = 0;
        int next = regimeIndex;
        for (int j = 0; j < transitionTick[regimeIndex].length; j++) {
            acc += transitionTick[regimeIndex][j];
            if (u < acc) {
                next = j;
                break;
            }
        }
        if (next == regimeIndex) {
            return;
        }
        String name = cfg.regimes().get(next).name();
        log.debug("sim regime: {} -> {}", cfg.regimes().get(regimeIndex).name(), name);
        regimeIndex = next;
        // Shock-entry gaps (a CPI/geopolitics surprise is a jump, not a fast drift):
        if ("RISK_OFF".equals(name)) {
            gap(-(0.010 + 0.020 * random.nextDouble()), -(3 + 5 * random.nextDouble()) * 1e-4);
        } else if ("INFLATION_SHOCK".equals(name)) {
            gap(-(0.005 + 0.010 * random.nextDouble()), +(5 + 8 * random.nextDouble()) * 1e-4);
        }
    }

    /** One-tick correlated gap: equities move by {@code eqJump}, the curve level by {@code lvlJump}. */
    private void gap(double eqJump, double lvlJump) {
        shockSign = -1;
        for (int i = 0; i < ids.length; i++) {
            double r = betaEq[i] * eqJump;
            price[i] = price[i] * Math.exp(r);
            bound(i);
            recordReturnForVolume(i, r); // a shock gap is a big-move tick — volume surges with it
        }
        lastLevelDelta += lvlJump; // consumed by the curve on this tick
    }

    /** Shared Student-t scale, normalized to unit variance: √(ν/χ²_ν) · √((ν−2)/ν). */
    private double studentTScale() {
        int nu = (int) Math.round(cfg.tDegreesOfFreedom());
        double chi2 = 0;
        for (int k = 0; k < nu; k++) {
            double g = random.nextGaussian();
            chi2 += g * g;
        }
        if (chi2 < 1e-12) {
            return 1.0; // pathological draw — fall back to Gaussian scale for this tick
        }
        return Math.sqrt(nu / chi2) * Math.sqrt((nu - 2.0) / nu);
    }

    public long priceScaled(int instrumentIndex) {
        return Math.max(MIN_PRICE_SCALED, Math.round(price[instrumentIndex] * 1_000_000));
    }

    public MarketRegime regime() {
        try {
            return MarketRegime.valueOf(cfg.regimes().get(regimeIndex).name());
        } catch (IllegalArgumentException e) {
            return MarketRegime.CALM; // unknown regime name — safe default for consumers
        }
    }

    /** -1 on the tick a shock regime was entered (for the narrative/curve consumers), else 0. */
    public int shockSign() {
        return shockSign;
    }

    /** This tick's RATES level change (fraction) — drives the curve simulator's level. */
    public double lastLevelDelta() {
        return lastLevelDelta;
    }

    /** This tick's RATES slope change (fraction) — drives the curve simulator's slope. */
    public double lastSlopeDelta() {
        return lastSlopeDelta;
    }

    /**
     * Regime-aware trade size (no per-instrument volatility term) for curve/linked instruments:
     * the base draw amplified by the current regime's activity — stress regimes trade heavier.
     * For factor names use {@link #nextQuantityScaled(int)} so volume also clusters with the move.
     */
    public long nextQuantityScaled() {
        return scaledQuantity(baseQuantityDraw(), regimeVolumeMultiple(), 1.0);
    }

    /**
     * Regime- AND volatility-aware trade size for factor instrument {@code i}. The base draw is
     * amplified by the regime's activity and by how large THIS tick's move was versus the name's
     * recent normal — so a big-move tick prints heavier (volume follows volatility) and stress
     * regimes trade heavier overall. This coupling is the "shape of traffic" that a regime change
     * makes visible on the tape, not just a change in price variance. Draws exactly one random,
     * like the no-arg form, so the deterministic RNG stream is unchanged.
     */
    public long nextQuantityScaled(int instrumentIndex) {
        double norm = emaAbsReturn[instrumentIndex] > 1e-12
                ? lastAbsReturn[instrumentIndex] / emaAbsReturn[instrumentIndex] : 1.0;
        double cluster = Math.min(VOL_CLUSTER_CAP, Math.max(VOL_CLUSTER_FLOOR, norm));
        return scaledQuantity(baseQuantityDraw(), regimeVolumeMultiple(), cluster);
    }

    private long baseQuantityDraw() {
        return random.nextInt(1000) + 1; // 1..1000 whole units, BEFORE regime/vol amplification
    }

    /** The current regime's activity multiple, reused as the volume surge factor — a 3× vol
     *  stress regime trades ~3× the calm baseline, the stylized volume-in-stress behaviour. */
    private double regimeVolumeMultiple() {
        return cfg.regimes().get(regimeIndex).volMultiple();
    }

    private static long scaledQuantity(long base, double regimeAmp, double clusterAmp) {
        long qty = Math.round(base * regimeAmp * clusterAmp);
        return Math.max(1L, qty) * 1_000_000L;
    }

    // ---- calibration plumbing ----

    private FactorModelConfig.InstrumentSpec specFor(String id) {
        for (FactorModelConfig.InstrumentSpec s : cfg.instruments()) {
            if (s.id().equals(id)) {
                return s;
            }
        }
        log.warn("sim calibration has no spec for {} — using generic equity-like defaults "
                + "(vol 20%, beta 1.0). Add it to sim-calibration.json.", id);
        return new FactorModelConfig.InstrumentSpec(id, 0.20, 1.0, 0.0);
    }

    /** Idio vol so TOTAL annual variance matches the spec: idio² = total² − systematic². */
    private double idioSigmaAnnual(FactorModelConfig.InstrumentSpec s, double rhoEqUsd) {
        double se = s.betaEquity() * cfg.equityFactorVolAnnual();
        double su = s.betaUsd() * cfg.usdFactorVolAnnual();
        double systematic = se * se + su * su + 2 * se * su * rhoEqUsd;
        double idioVar = s.annualVol() * s.annualVol() - systematic;
        double floor = 0.1 * s.annualVol(); // never a purely deterministic function of the factors
        return Math.max(floor, idioVar > 0 ? Math.sqrt(idioVar) : floor);
    }

    private double baseEqUsdCorrelation() {
        int calm = cfg.regimeIndex("CALM");
        return calm >= 0 ? cfg.regimes().get(calm).factorCorrelation()[F_EQ][F_USD] : 0.0;
    }

    /** Daily matrix → per-tick: off-diagonals scale by dtDays; diagonal absorbs the rest. */
    private static double[][] perTickTransition(double[][] daily, double dtDays) {
        int n = daily.length;
        double[][] tick = new double[n][n];
        for (int i = 0; i < n; i++) {
            double off = 0;
            for (int j = 0; j < n; j++) {
                if (i != j) {
                    tick[i][j] = daily[i][j] * dtDays;
                    off += tick[i][j];
                }
            }
            tick[i][i] = 1.0 - off;
        }
        return tick;
    }

    /** Cholesky with escalating diagonal jitter; a matrix that still fails is a config bug. */
    private static double[][] choleskyWithJitter(double[][] corr, String regimeName) {
        for (double jitter : new double[]{0, 1e-10, 1e-8, 1e-6, 1e-4}) {
            double[][] l = tryCholesky(corr, jitter);
            if (l != null) {
                if (jitter > 0) {
                    log.warn("correlation matrix for regime {} needed diagonal jitter {} to be PSD",
                            regimeName, jitter);
                }
                return l;
            }
        }
        throw new IllegalArgumentException(
                "correlation matrix for regime " + regimeName + " is not positive semi-definite");
    }

    private static double[][] tryCholesky(double[][] a, double jitter) {
        int n = a.length;
        double[][] l = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j <= i; j++) {
                double sum = a[i][j] + (i == j ? jitter : 0);
                for (int k = 0; k < j; k++) {
                    sum -= l[i][k] * l[j][k];
                }
                if (i == j) {
                    if (sum <= 0) {
                        return null;
                    }
                    l[i][i] = Math.sqrt(sum);
                } else {
                    l[i][j] = sum / l[j][j];
                }
            }
        }
        return l;
    }

    private static double[] multiply(double[][] l, double[] z) {
        double[] out = new double[z.length];
        for (int i = 0; i < z.length; i++) {
            double s = 0;
            for (int j = 0; j <= i; j++) {
                s += l[i][j] * z[j];
            }
            out[i] = s;
        }
        return out;
    }
}
