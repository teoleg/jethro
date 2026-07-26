package io.jethro.trading.algo.strategy;

import io.jethro.domain.Side;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Factor-level, vol-gated trend follower (ADR-0070). Where {@link MomentumStrategy} reacts to each
 * NAME's own move, this reads the shared <b>equity factor</b> the whole complex loads on and takes a
 * single directional stance from it — because in this market the exploitable structure lives at the
 * factor level (regime persistence + vol clustering), not in per-name price which is dominated by
 * idiosyncratic noise (measured; ADR-0069/0070). It is the owner's thesis in code: a risk sensor and a
 * trend sensor on the factor, reacting — never a forecast.
 *
 * <p><b>Signal (per evaluation cycle), all from OBSERVABLE prices only (invariant 8/9 — no sim oracle):</b>
 * <pre>
 *   1. Factor step return  f_t = mean over the equity basket of ln(p_i,t / p_i,t-1)   (equal-weight breadth)
 *   2. Factor trend        z   = Σf / (σ(f)·√n)  over the last {@code trendWindow} steps   (SIGNED)
 *   3. Factor vol proxy    v   = mean|f| over the last {@code volWindow} steps
 *      vol ratio  r = v / baseline, baseline = slow EWMA of v (lagged; frozen upward while ELEVATED,
 *      exactly as {@link VolatilityRegime}, ADR-0051) → ELEVATED when r ≥ {@code volUpper} (hysteresis
 *      back to CALM under {@code volLower}).
 * </pre>
 *
 * <p><b>Stance → per-name candidates</b> (the lifecycle sizes/caps/exits; shorts default off, so a SELL
 * only ever reduces a long toward flat — ADR-0018/0019):
 * <ul>
 *   <li><b>CUT</b> — vol ELEVATED, OR a confirmed down-trend ({@code z ≤ −trendThreshold}): emit SELL for
 *       every basket name → the book de-risks to flat. Danger overrides trend (CLAUDE.md triage rule 4):
 *       the down-drift regimes here are precisely the high-vol ones, so cutting on the vol spike is what
 *       sidesteps them.</li>
 *   <li><b>LONG</b> — vol CALM and a confirmed up-trend ({@code z ≥ +trendThreshold}): emit BUY for every
 *       basket name → the book builds/holds the long (capped, vol-scaled). Let winners run.</li>
 *   <li><b>HOLD</b> — otherwise (chop / weak trend, contained vol): emit nothing; existing positions ride
 *       under the lifecycle's own stops. No churn.</li>
 * </ul>
 * Re-emitting the stance every cycle is self-limiting: once max-long the extra BUYs are suppressed, once
 * flat the extra SELLs have nothing to reduce (shorts off) — so a sustained regime trades once, not each tick.
 *
 * <p>Each emitted candidate carries the NAME's own reference/price/changeBps and the name's own window
 * z-score (so the lifecycle's warm-up vol-scaling sizes it exactly as a momentum signal would); the
 * factor read decides only WHETHER and WHICH SIDE to fire. Statistical readings (z, vol ratio) are
 * {@code double} — never money (invariant 1); all price arithmetic stays {@link BigDecimal}.
 *
 * <p>Not thread-safe: evaluated from the single strategy thread. The basket is the set of equity-factor
 * instrument ids supplied at construction (the refdata EQUITY universe); a non-basket name in the
 * observations is ignored here. An empty basket makes the strategy a no-op (emits nothing).
 */
public final class FactorTrendStrategy implements Strategy {

    private final Set<String> basket;          // equity-factor instrument ids (refdata EQUITY universe)
    private final int trendWindow;             // factor-return steps in the signed trend z window
    private final int volWindow;               // factor-return steps in the vol proxy window
    private final double trendThresholdSigmas; // |factor z| at/above which the trend is "confirmed"
    private final BigDecimal volUpper;         // vol ratio ≥ this → ELEVATED (cut)
    private final BigDecimal volLower;         // vol ratio ≤ this → back to CALM (hysteresis)
    private final double volLambda;            // EWMA decay for the slow vol baseline

    // Per-name last price (for the 1-step factor return) and each name's own price window (for its z).
    private final Map<String, BigDecimal> lastPrice = new HashMap<>();
    private final Map<String, Deque<BigDecimal>> nameWindow = new HashMap<>();

    // Factor-return history (the shared series everything is computed on) and the slow vol baseline.
    private final Deque<Double> factorReturns = new ArrayDeque<>();
    private double volBaseline = Double.NaN; // NaN until seeded
    private boolean elevated = false;        // vol-regime state (hysteresis)

    public FactorTrendStrategy(Set<String> basket, int trendWindow, int volWindow,
                               double trendThresholdSigmas, BigDecimal volUpper, BigDecimal volLower,
                               double volLambda) {
        if (trendWindow < 2 || volWindow < 2) {
            throw new IllegalArgumentException("windows must be >= 2");
        }
        if (!(trendThresholdSigmas > 0)) {
            throw new IllegalArgumentException("trendThresholdSigmas must be positive");
        }
        this.basket = Set.copyOf(basket);
        this.trendWindow = trendWindow;
        this.volWindow = volWindow;
        this.trendThresholdSigmas = trendThresholdSigmas;
        this.volUpper = volUpper;
        this.volLower = volLower;
        this.volLambda = volLambda;
    }

    @Override
    public List<TradeSignal> evaluate(List<Strategy.Observation> observations) {
        // 1. Advance each basket name's price/window and accumulate this cycle's factor step return.
        double sumReturn = 0.0;
        int contributors = 0;
        for (Strategy.Observation obs : observations) {
            if (obs == null || obs.stale() || obs.price() == null || obs.price().signum() <= 0
                    || !basket.contains(obs.instrumentId())) {
                continue; // ignore stale/absent marks and everything outside the equity-factor basket
            }
            BigDecimal prev = lastPrice.put(obs.instrumentId(), obs.price());
            Deque<BigDecimal> win = nameWindow.computeIfAbsent(obs.instrumentId(), k -> new ArrayDeque<>());
            win.addLast(obs.price());
            while (win.size() > trendWindow + 1) {
                win.removeFirst();
            }
            if (prev != null && prev.signum() > 0) {
                sumReturn += Math.log(obs.price().doubleValue() / prev.doubleValue());
                contributors++;
            }
        }
        if (contributors == 0) {
            return List.of(); // first sight of the basket (or nothing fresh) — no factor step yet
        }

        // 2. Equal-weight factor step return, appended to the shared history.
        double factorStep = sumReturn / contributors;
        factorReturns.addLast(factorStep);
        int cap = Math.max(trendWindow, volWindow);
        while (factorReturns.size() > cap) {
            factorReturns.removeFirst();
        }

        // 3. Vol regime on the factor series (needs a full vol window); baseline updated AFTER classifying
        //    and frozen upward while ELEVATED so a sustained turbulent regime stays flagged (ADR-0051).
        boolean volReady = factorReturns.size() >= volWindow;
        double volProxy = volReady ? meanAbsLast(volWindow) : Double.NaN;
        if (volReady) {
            double ratio = (Double.isNaN(volBaseline) || volBaseline <= 0) ? 1.0 : volProxy / volBaseline;
            elevated = classifyVol(ratio, elevated);
            if (Double.isNaN(volBaseline)) {
                volBaseline = volProxy;
            } else {
                double ewma = volLambda * volBaseline + (1.0 - volLambda) * volProxy;
                volBaseline = elevated ? Math.min(ewma, volBaseline) : ewma;
            }
        }

        // 4. Signed factor trend (needs a full trend window).
        boolean trendReady = factorReturns.size() >= trendWindow;
        double z = trendReady ? factorZScore(trendWindow) : 0.0;

        // 5. Stance. Danger (elevated vol) or a confirmed down-trend → CUT; calm confirmed up-trend → LONG;
        //    else HOLD. Nothing fires until at least the trend window is warm.
        Side side;
        if (!trendReady) {
            return List.of();
        } else if (elevated || z <= -trendThresholdSigmas) {
            side = Side.SELL; // de-risk to flat (shorts off) — sidesteps the high-vol down-drift regimes
        } else if (z >= trendThresholdSigmas) {
            side = Side.BUY;  // ride the calm up-trend
        } else {
            return List.of(); // HOLD — chop / weak trend, contained vol
        }

        // 6. One candidate per basket name that has a warm own-window and a fresh mark this cycle.
        String kind = "factor-trend";
        List<TradeSignal> out = new ArrayList<>();
        for (Strategy.Observation obs : observations) {
            if (obs == null || obs.stale() || obs.price() == null || obs.price().signum() <= 0
                    || !basket.contains(obs.instrumentId())) {
                continue;
            }
            Deque<BigDecimal> win = nameWindow.get(obs.instrumentId());
            if (win == null || win.size() < trendWindow + 1) {
                continue; // this name's own window not warm — no honest reference price yet
            }
            BigDecimal reference = win.peekFirst();
            if (reference == null || reference.signum() <= 0) {
                continue;
            }
            BigDecimal changeBps = obs.price().subtract(reference)
                    .divide(reference, 8, RoundingMode.HALF_EVEN)
                    .multiply(BigDecimal.valueOf(10_000));
            double nameZ = nameZScore(win);
            out.add(new TradeSignal(obs.instrumentId(), side, reference, obs.price(), changeBps, nameZ, nameZ, kind));
        }
        return out;
    }

    /** Mean absolute value of the last {@code n} factor returns — a sqrt-free vol proxy (ADR-0051 style). */
    private double meanAbsLast(int n) {
        int skip = Math.max(0, factorReturns.size() - n);
        double sum = 0.0;
        int i = 0, taken = 0;
        for (double f : factorReturns) {
            if (i++ < skip) {
                continue;
            }
            sum += Math.abs(f);
            taken++;
        }
        return taken == 0 ? 0.0 : sum / taken;
    }

    private boolean classifyVol(double ratio, boolean prev) {
        if (ratio >= volUpper.doubleValue()) {
            return true;
        }
        if (ratio <= volLower.doubleValue()) {
            return false;
        }
        return prev; // hysteresis inside the band
    }

    /** Signed factor z over the last {@code n} steps: Σf / (σ(f)·√n); ±∞ for a perfectly steady trend. */
    private double factorZScore(int n) {
        int skip = Math.max(0, factorReturns.size() - n);
        DescriptiveStatistics stats = new DescriptiveStatistics();
        double sum = 0.0;
        int i = 0;
        for (double f : factorReturns) {
            if (i++ < skip) {
                continue;
            }
            stats.addValue(f);
            sum += f;
        }
        double sigma = stats.getStandardDeviation();
        if (sigma == 0.0) {
            return sum == 0.0 ? 0.0 : (sum > 0 ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY);
        }
        return sum / (sigma * Math.sqrt(stats.getN()));
    }

    /** A single name's own signed window z — same statistic MomentumStrategy sizes on. */
    private double nameZScore(Deque<BigDecimal> window) {
        DescriptiveStatistics returns = new DescriptiveStatistics();
        double previous = Double.NaN;
        double move = 0.0;
        for (BigDecimal p : window) {
            double price = p.doubleValue();
            if (!Double.isNaN(previous) && previous > 0 && price > 0) {
                double r = Math.log(price / previous);
                returns.addValue(r);
                move += r;
            }
            previous = price;
        }
        double sigma = returns.getStandardDeviation();
        if (sigma == 0.0) {
            return move == 0.0 ? 0.0 : (move > 0 ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY);
        }
        return move / (sigma * Math.sqrt(returns.getN()));
    }

    @Override
    public String name() {
        return "factor-trend";
    }
}
