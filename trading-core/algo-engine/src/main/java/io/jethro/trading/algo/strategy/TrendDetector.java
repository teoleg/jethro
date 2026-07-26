package io.jethro.trading.algo.strategy;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Price-derived trend/chop detector (ADR-0044): infers each instrument's regime from OBSERVABLE
 * prices only — the identical computation in sim, live and replay (invariant 8 / ADR-0029). It reads
 * NO sim internals; the sim's CALM/RISK_OFF label is an oracle that does not exist in production and
 * is never consulted here.
 *
 * <p>Signal: Kaufman's Efficiency Ratio over a rolling window of the last {@code window} price steps —
 * {@code ER = |P_t − P_{t−n}| / Σ|P_i − P_{i−1}| ∈ [0,1]}; ~1 a clean trend, ~0 chop. A regime flip
 * needs ER to cross a band ({@code ≥ upper → TREND}, {@code ≤ lower → CHOP}); between the bands the
 * current regime holds (hysteresis) so the detector does not whipsaw at the boundary. Until a name's
 * window fills it is {@link Regime#UNKNOWN} (the caller uses its default). Cross-sectional breadth —
 * the mean ER across all names with a full window — is the market-wide read; a name still inside the
 * neutral band on first warm-up adopts the breadth regime, but a name with a decisive ER always
 * follows its own.
 *
 * <p>All price arithmetic is exact {@link BigDecimal} (invariant 1); the ratio (a dimensionless
 * indicator, not money) is compared against BigDecimal bands — no binary floating point touches a
 * price. Not thread-safe: updated and read from the single strategy-evaluation thread.
 */
public final class TrendDetector {

    public enum Regime { TREND, CHOP, UNKNOWN }

    private static final MathContext MC = MathContext.DECIMAL64;

    private final int window;          // number of price STEPS in the ER window (keeps window+1 prices)
    private final BigDecimal upperBand; // ER ≥ upper → TREND
    private final BigDecimal lowerBand; // ER ≤ lower → CHOP

    private final Map<String, Deque<BigDecimal>> prices = new LinkedHashMap<>();
    private final Map<String, Regime> regime = new LinkedHashMap<>();
    private final Map<String, BigDecimal> lastEr = new LinkedHashMap<>();
    private volatile Regime breadth = Regime.UNKNOWN;

    /**
     * @param window    price steps in the efficiency-ratio window (≥2)
     * @param upperBand ER at/above which a name is TREND
     * @param lowerBand ER at/below which a name is CHOP (must be ≤ upperBand)
     */
    public TrendDetector(int window, BigDecimal upperBand, BigDecimal lowerBand) {
        this.window = Math.max(2, window);
        this.upperBand = upperBand;
        this.lowerBand = lowerBand;
    }

    /** Feed one evaluation cycle's observations; recomputes every seen name's regime and the breadth. */
    public void update(List<Strategy.Observation> observations) {
        for (Strategy.Observation o : observations) {
            if (o == null || o.price() == null || o.stale()) {
                continue; // a stale mark repeats the last price — never advance the window on it
            }
            Deque<BigDecimal> win = prices.computeIfAbsent(o.instrumentId(), k -> new ArrayDeque<>());
            win.addLast(o.price());
            while (win.size() > window + 1) {
                win.removeFirst();
            }
            if (win.size() == window + 1) {
                BigDecimal er = efficiencyRatio(win);
                lastEr.put(o.instrumentId(), er);
                regime.put(o.instrumentId(), classify(er, regime.getOrDefault(o.instrumentId(), Regime.UNKNOWN)));
            }
        }
        recomputeBreadth();
    }

    /**
     * Kaufman's efficiency ratio over a price window: {@code ER = |last − first| / Σ|step| ∈ [0,1]} —
     * ~1 a clean trend, ~0 chop. A dead-flat window (Σ=0) reads as 0 (the extreme of non-trending);
     * fewer than two prices has no steps to measure and also reads 0.
     *
     * <p>Exposed as a pure static so every consumer of "how directional is this window?" shares ONE
     * definition of the ratio — this detector's regime classification and the EWMAC trend forecaster's
     * quality weight ({@code EwmacTrendForecaster}) among them. Dimensionless: exact BigDecimal price
     * arithmetic in, a ratio out — no money number is produced here (invariant 1 / ADR-0016).
     */
    public static BigDecimal efficiencyRatio(List<BigDecimal> prices) {
        if (prices == null || prices.size() < 2) {
            return BigDecimal.ZERO;
        }
        BigDecimal net = prices.get(prices.size() - 1).subtract(prices.get(0)).abs();
        BigDecimal sumSteps = BigDecimal.ZERO;
        for (int i = 1; i < prices.size(); i++) {
            sumSteps = sumSteps.add(prices.get(i).subtract(prices.get(i - 1)).abs());
        }
        if (sumSteps.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return net.divide(sumSteps, MC);
    }

    private BigDecimal efficiencyRatio(Deque<BigDecimal> win) {
        return efficiencyRatio(new ArrayList<>(win));
    }

    /** Apply the bands with hysteresis; a name in the neutral zone on first warm-up adopts breadth. */
    private Regime classify(BigDecimal er, Regime prev) {
        if (er.compareTo(upperBand) >= 0) {
            return Regime.TREND;
        }
        if (er.compareTo(lowerBand) <= 0) {
            return Regime.CHOP;
        }
        if (prev != Regime.UNKNOWN) {
            return prev; // hysteresis: hold the established regime inside the band
        }
        return breadth; // neutral on first read → follow the market (may be UNKNOWN → caller defaults)
    }

    private void recomputeBreadth() {
        if (lastEr.isEmpty()) {
            return;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal er : lastEr.values()) {
            sum = sum.add(er);
        }
        BigDecimal mean = sum.divide(new BigDecimal(lastEr.size()), MC);
        if (mean.compareTo(upperBand) >= 0) {
            breadth = Regime.TREND;
        } else if (mean.compareTo(lowerBand) <= 0) {
            breadth = Regime.CHOP;
        }
        // inside the band → keep the previous breadth (hysteresis)
    }

    /** The regime for an instrument, or {@link Regime#UNKNOWN} until its window fills. */
    public Regime regimeFor(String instrumentId) {
        return regime.getOrDefault(instrumentId, Regime.UNKNOWN);
    }

    /** Market-wide breadth (mean ER classified), UNKNOWN until at least one name has a full window. */
    public Regime breadth() {
        return breadth;
    }

    /** Last computed efficiency ratio for an instrument, or null if not yet full. */
    public BigDecimal efficiencyRatio(String instrumentId) {
        return lastEr.get(instrumentId);
    }

    /** Snapshot of every known instrument's regime (for the API/UI). */
    public Map<String, Regime> regimes() {
        return Map.copyOf(regime);
    }
}
