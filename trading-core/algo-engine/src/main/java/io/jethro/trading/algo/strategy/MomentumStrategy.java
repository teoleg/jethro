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
    private final Map<String, Deque<BigDecimal>> history = new HashMap<>();

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
    }

    /** Feeds one observation snapshot and returns any signals it triggers. */
    @Override
    public List<TradeSignal> evaluate(List<Strategy.Observation> observations) {
        List<TradeSignal> signals = new ArrayList<>();
        for (Strategy.Observation obs : observations) {
            if (obs.stale()) {
                continue; // don't trade off a stale mark
            }
            Deque<BigDecimal> window = history.computeIfAbsent(obs.instrumentId(), k -> new ArrayDeque<>());
            window.addLast(obs.price());
            while (window.size() > lookback + 1) {
                window.removeFirst();
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

            double z = windowZScore(window);
            if (z >= thresholdSigmas) {
                signals.add(new TradeSignal(obs.instrumentId(), Side.BUY, reference, obs.price(), changeBps, z));
            } else if (z <= -thresholdSigmas) {
                signals.add(new TradeSignal(obs.instrumentId(), Side.SELL, reference, obs.price(), changeBps, z));
            }
        }
        return signals;
    }

    /** Signed z: window log-move divided by (per-return σ · √lookback); ±∞ for a steady trend. */
    private double windowZScore(Deque<BigDecimal> window) {
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
