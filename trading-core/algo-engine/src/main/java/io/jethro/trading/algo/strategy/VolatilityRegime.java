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
 * Price-derived volatility-regime detector (ADR-0051): the trading path SENSES whether the market is
 * in an elevated-volatility (risk-off) state from OBSERVABLE prices — never from the sim's regime
 * label. The identical computation runs in sim, live and replay (invariant 8 / ADR-0029), so risk-off
 * sizing behaves the same everywhere instead of only firing in sim (where a label exists).
 *
 * <p>Signal: a sqrt-free relative-volatility proxy per instrument — mean absolute step move over a
 * rolling window ÷ the current price ({@code (Σ|Δp|/N) / P}). The market reading is the mean across
 * names with a full window, compared to its own slow EWMA baseline: {@code ELEVATED} when the ratio
 * crosses the upper band, back to {@code CALM} under the lower band (hysteresis, so it doesn't flap).
 * Until a name's window fills the regime is {@link Regime#UNKNOWN} (caller applies no shrink).
 *
 * <p>All price arithmetic is exact {@link BigDecimal} (invariant 1); the vol proxy and the ratio are
 * dimensionless indicators, not money. Not thread-safe: updated/read from the single sizing thread.
 */
public final class VolatilityRegime {

    public enum Regime { CALM, ELEVATED, UNKNOWN }

    public static final int DEFAULT_WINDOW = 30;
    public static final String DEFAULT_UPPER = "1.5"; // ELEVATED when market vol ≥ 1.5× its baseline
    public static final String DEFAULT_LOWER = "1.1"; // back to CALM under 1.1× (hysteresis band)
    public static final String DEFAULT_LAMBDA = "0.97"; // slow baseline so a spike stands out

    private static final MathContext MC = MathContext.DECIMAL64;

    private final int window;
    private final BigDecimal upperFactor;
    private final BigDecimal lowerFactor;
    private final BigDecimal lambda;

    private final Map<String, Deque<BigDecimal>> prices = new LinkedHashMap<>();
    private BigDecimal baseline; // EWMA of the market vol proxy; null until seeded
    private BigDecimal lastRatio = BigDecimal.ONE;
    private Regime regime = Regime.UNKNOWN;

    /** Defaults (kept in sync between the live path and the backtest for parity). */
    public VolatilityRegime() {
        this(DEFAULT_WINDOW, new BigDecimal(DEFAULT_UPPER), new BigDecimal(DEFAULT_LOWER), new BigDecimal(DEFAULT_LAMBDA));
    }

    public VolatilityRegime(int window, BigDecimal upperFactor, BigDecimal lowerFactor, BigDecimal lambda) {
        this.window = Math.max(3, window);
        this.upperFactor = upperFactor;
        this.lowerFactor = lowerFactor;
        this.lambda = lambda;
    }

    /** Feed one evaluation cycle's observations; recomputes the market volatility regime. */
    public void update(List<Strategy.Observation> observations) {
        BigDecimal sum = BigDecimal.ZERO;
        int count = 0;
        for (Strategy.Observation o : observations) {
            if (o == null || o.price() == null || o.stale() || o.price().signum() <= 0) {
                continue; // never advance the window on a stale/absent mark
            }
            Deque<BigDecimal> win = prices.computeIfAbsent(o.instrumentId(), k -> new ArrayDeque<>());
            win.addLast(o.price());
            while (win.size() > window + 1) {
                win.removeFirst();
            }
            if (win.size() == window + 1) {
                BigDecimal rv = relVol(win);
                if (rv != null) {
                    sum = sum.add(rv);
                    count++;
                }
            }
        }
        if (count == 0) {
            return; // nothing has a full window yet — hold (UNKNOWN until then)
        }
        BigDecimal marketVol = sum.divide(new BigDecimal(count), MC);
        BigDecimal ratio = (baseline == null || baseline.signum() == 0) ? BigDecimal.ONE
                : marketVol.divide(baseline, MC);
        lastRatio = ratio;
        regime = classify(ratio, regime);
        // Update the slow baseline AFTER classifying, so a spike is measured against the prior trend.
        baseline = baseline == null ? marketVol
                : lambda.multiply(baseline).add(BigDecimal.ONE.subtract(lambda).multiply(marketVol));
    }

    /** Mean absolute step move over the window ÷ current price — a sqrt-free relative-vol proxy. */
    private BigDecimal relVol(Deque<BigDecimal> win) {
        List<BigDecimal> ps = new ArrayList<>(win);
        BigDecimal last = ps.get(ps.size() - 1);
        if (last.signum() <= 0) {
            return null;
        }
        BigDecimal sumAbs = BigDecimal.ZERO;
        for (int i = 1; i < ps.size(); i++) {
            sumAbs = sumAbs.add(ps.get(i).subtract(ps.get(i - 1)).abs());
        }
        BigDecimal meanAbs = sumAbs.divide(new BigDecimal(ps.size() - 1), MC);
        return meanAbs.divide(last, MC);
    }

    private Regime classify(BigDecimal ratio, Regime prev) {
        if (ratio.compareTo(upperFactor) >= 0) {
            return Regime.ELEVATED;
        }
        if (ratio.compareTo(lowerFactor) <= 0) {
            return Regime.CALM;
        }
        return prev == Regime.UNKNOWN ? Regime.CALM : prev; // hysteresis inside the band
    }

    public Regime regime() {
        return regime;
    }

    /** Market vol ÷ baseline on the last update (for telemetry/UI). */
    public BigDecimal marketVolRatio() {
        return lastRatio;
    }
}
