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

/**
 * A deterministic, volatility-adaptive momentum toy strategy (step 8). Keeps a rolling
 * price window per instrument and signals when the move over the window is statistically
 * unusual <em>for that instrument</em>:
 *
 * <pre>
 *   returns rᵢ = ln(pᵢ/pᵢ₋₁) over the window (Commons Math sample std-dev, ADR-0020)
 *   z = Σrᵢ / (σ(r) · √lookback)          — the window move in units of its own vol
 *   BUY  when z ≥ +thresholdSigmas, SELL when z ≤ −thresholdSigmas
 *   subject to |move| ≥ minSignalBps      — floor against economically meaningless dust
 * </pre>
 *
 * A fixed-bps threshold cannot fit instruments whose vols differ 10× (equities vs
 * Treasury futures): any constant either fires on noise or never fires. The z-score
 * self-calibrates per instrument. A perfectly steady trend (σ = 0, move ≠ 0) counts as
 * infinite z — trending, so it signals if above the floor. Deterministic given the same
 * observations (invariant: it proposes, never trades — ADR-0018/0019 downstream).
 */
public final class MomentumStrategy implements Strategy {

    private final int lookback;
    private final double thresholdSigmas;
    private final BigDecimal minSignalBps;
    private final double volumeConfirmMin; // ADR-0033: require relativeVolume ≥ this; 0 disables
    private final double baselineLambda;   // EWMA decay for the slow vol baseline (halflife = lookback)
    /** ADR-0052: when non-null, threshold/floor/lookback/volume-confirm are read from here on every
     *  evaluate (live tuning). Null on the backtest/walk-forward path, which must stay reproducible. */
    private final SignalParams live;
    private final Map<String, Deque<BigDecimal>> history = new HashMap<>();
    private final Map<String, Baseline> baselines = new HashMap<>();

    /** A slow EWMA of squared returns — the instrument's "normal" per-return vol, updated with a
     *  one-tick lag so a move is scored against the vol that prevailed BEFORE it (not the vol its
     *  own spike inflates). Halflife = lookback returns, so it spans ~one window and recovers over
     *  the next. Purely a reporting baseline; it never changes which signals fire. */
    private static final class Baseline {
        private double var;
        private long n;

        double variance() {
            return var;
        }

        boolean ready(long lookback) {
            return n >= lookback;
        }

        void update(double r, double lambda) {
            double r2 = r * r;
            var = n == 0 ? r2 : lambda * var + (1.0 - lambda) * r2;
            n++;
        }
    }

    /** Without volume confirmation (the backtest/legacy shape) — gate disabled. */
    public MomentumStrategy(int lookback, double thresholdSigmas, BigDecimal minSignalBps) {
        this(lookback, thresholdSigmas, minSignalBps, 0.0);
    }

    /**
     * @param lookback         number of returns in the window (window = lookback+1 prices).
     * @param thresholdSigmas  z-score at which a signal fires (e.g. 2.5).
     * @param minSignalBps     minimum absolute move, in bps, for any signal.
     * @param volumeConfirmMin minimum relativeVolume for a signal to fire (ADR-0033) — a breakout
     *                         on thinner participation is discarded; 0 disables the gate.
     */
    public MomentumStrategy(int lookback, double thresholdSigmas, BigDecimal minSignalBps,
                            double volumeConfirmMin) {
        this(lookback, thresholdSigmas, minSignalBps, volumeConfirmMin, null);
    }

    /**
     * Live-tunable variant (ADR-0052): {@code live} overrides threshold/floor/lookback/volume-confirm
     * on every evaluate. The constructor args remain the fallback (used until an override is set and
     * whenever a live getter returns a non-usable value). Only the live strategy passes a source;
     * the backtest never does, so its measurement stays reproducible.
     */
    public MomentumStrategy(int lookback, double thresholdSigmas, BigDecimal minSignalBps,
                            double volumeConfirmMin, SignalParams live) {
        if (lookback < 2) {
            throw new IllegalArgumentException("lookback must be >= 2");
        }
        if (thresholdSigmas <= 0) {
            throw new IllegalArgumentException("thresholdSigmas must be positive");
        }
        this.lookback = lookback;
        this.thresholdSigmas = thresholdSigmas;
        this.minSignalBps = minSignalBps;
        this.volumeConfirmMin = Math.max(0.0, volumeConfirmMin);
        this.baselineLambda = Math.exp(-Math.log(2.0) / lookback); // halflife = lookback returns
        this.live = live;
    }

    /** Feeds one observation snapshot and returns any signals it triggers. */
    @Override
    public List<TradeSignal> evaluate(List<Strategy.Observation> observations) {
        // ADR-0052: snapshot the tunable params ONCE per cycle. With a live source they reflect the
        // current runtime override; without one they are the construction-time constants (backtest).
        // Each getter falls back to the constant when the live value is unusable, so a bad override
        // can never wedge the detector. baselineLambda stays at its constructor value — it drives only
        // the reporting baseline (never a signal), so a live lookback change doesn't need to rebuild it.
        int lookback = this.lookback;
        double thresholdSigmas = this.thresholdSigmas;
        BigDecimal minSignalBps = this.minSignalBps;
        double volumeConfirmMin = this.volumeConfirmMin;
        if (live != null) {
            int l = live.lookback();
            if (l >= 2) {
                lookback = l;
            }
            double t = live.thresholdSigmas();
            if (t > 0 && Double.isFinite(t)) {
                thresholdSigmas = t;
            }
            BigDecimal floor = live.minSignalBps();
            if (floor != null && floor.signum() >= 0) {
                minSignalBps = floor;
            }
            double vc = live.volumeConfirmMin();
            volumeConfirmMin = Double.isFinite(vc) ? Math.max(0.0, vc) : this.volumeConfirmMin;
        }
        List<TradeSignal> signals = new ArrayList<>();
        for (Strategy.Observation obs : observations) {
            if (obs.stale()) {
                continue; // don't trade off a stale mark
            }
            Deque<BigDecimal> window = history.computeIfAbsent(obs.instrumentId(), k -> new ArrayDeque<>());
            BigDecimal previousPrice = window.peekLast();
            window.addLast(obs.price());
            while (window.size() > lookback + 1) {
                window.removeFirst();
            }
            // Update the slow vol baseline from THIS tick's return, but capture its pre-update state
            // first: a move is scored against the vol that prevailed BEFORE it (one-tick lag), so a
            // spike does not inflate the very σ it is measured against. Warms up on every tick,
            // including before the window is full.
            Baseline baseline = baselines.computeIfAbsent(obs.instrumentId(), k -> new Baseline());
            double baselineVarBefore = baseline.variance();
            boolean baselineReady = baseline.ready(lookback);
            if (previousPrice != null) {
                double p0 = previousPrice.doubleValue();
                double p1 = obs.price().doubleValue();
                if (p0 > 0 && p1 > 0) {
                    baseline.update(Math.log(p1 / p0), baselineLambda);
                }
            }
            if (window.size() < lookback + 1) {
                continue; // not enough history yet
            }
            BigDecimal reference = window.peekFirst();
            BigDecimal changeBps = obs.price().subtract(reference)
                    .divide(reference, 8, RoundingMode.HALF_EVEN)
                    .multiply(BigDecimal.valueOf(10_000));
            if (changeBps.abs().compareTo(minSignalBps) < 0) {
                continue; // below the dust floor regardless of z
            }
            // Volume confirmation (ADR-0033): only act on a move the market participated in — a
            // breakout on thin volume is discarded. Neutral (relativeVolume 1.0) always passes, so
            // a caller without volume (the backtest) is unaffected.
            if (volumeConfirmMin > 0 && obs.relativeVolume() < volumeConfirmMin) {
                continue;
            }

            double move = windowMove(window);
            double z = windowZScore(window, lookback);
            // Honest reading: the same move vs the slow baseline vol (reporting only). Until the
            // baseline is warm (or degenerate), fall back to the in-window z so it never misleads.
            double baselineZ = baselineReady && baselineVarBefore > 0
                    ? move / (Math.sqrt(baselineVarBefore) * Math.sqrt(lookback))
                    : z;
            if (z >= thresholdSigmas) {
                signals.add(new TradeSignal(obs.instrumentId(), Side.BUY, reference, obs.price(), changeBps, z, baselineZ));
            } else if (z <= -thresholdSigmas) {
                signals.add(new TradeSignal(obs.instrumentId(), Side.SELL, reference, obs.price(), changeBps, z, baselineZ));
            }
        }
        return signals;
    }

    /** Signed log-move over the window (Σ returns = ln(last/first) by telescoping). */
    private static double windowMove(Deque<BigDecimal> window) {
        double previous = Double.NaN;
        double move = 0.0;
        for (BigDecimal p : window) {
            double price = p.doubleValue();
            if (!Double.isNaN(previous) && previous > 0 && price > 0) {
                move += Math.log(price / previous);
            }
            previous = price;
        }
        return move;
    }

    /** Signed z: window log-move divided by (per-return σ · √lookback); ±∞ for a steady trend. */
    private double windowZScore(Deque<BigDecimal> window, int lookback) {
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
            // Every return identical: flat (move 0 → z 0) or a perfectly steady trend (±∞).
            return move == 0.0 ? 0.0 : (move > 0 ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY);
        }
        return move / (sigma * Math.sqrt(lookback));
    }

    @Override
    public String name() {
        return "momentum";
    }
}
