package io.jethro.trading.algo.strategy;

import io.jethro.domain.Side;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A deterministic momentum (trend-following) toy strategy (step 8). Keeps a short price
 * window per instrument and signals BUY when the price has risen at least {@code
 * thresholdBps} over the window, SELL when it has fallen as much. Pure and stateful:
 * given the same sequence of observations it emits the same signals — exact decimals, no
 * float (invariant 1). It proposes; it never trades (invariant 7).
 *
 * <p>Not investment logic — a stand-in to exercise the candidate → guardrail → attention
 * path until real strategies (and the ADR-0010 frontier tier) arrive.
 */
public final class MomentumStrategy {

    private final int lookback;
    private final BigDecimal thresholdBps;
    private final Map<String, Deque<BigDecimal>> history = new HashMap<>();

    /**
     * @param lookback     number of prior observations to compare against (window length).
     * @param thresholdBps move, in basis points, needed to fire a signal (e.g. 50 = 0.50%).
     */
    public MomentumStrategy(int lookback, BigDecimal thresholdBps) {
        if (lookback < 1) {
            throw new IllegalArgumentException("lookback must be >= 1");
        }
        this.lookback = lookback;
        this.thresholdBps = thresholdBps;
    }

    /** Feeds one observation snapshot and returns any signals it triggers. */
    public List<TradeSignal> evaluate(List<Observation> observations) {
        List<TradeSignal> signals = new ArrayList<>();
        for (Observation obs : observations) {
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
            if (changeBps.compareTo(thresholdBps) >= 0) {
                signals.add(new TradeSignal(obs.instrumentId(), Side.BUY, reference, obs.price(), changeBps));
            } else if (changeBps.compareTo(thresholdBps.negate()) <= 0) {
                signals.add(new TradeSignal(obs.instrumentId(), Side.SELL, reference, obs.price(), changeBps));
            }
        }
        return signals;
    }

    /** One instrument's current mark for the strategy to consider. */
    public record Observation(String instrumentId, BigDecimal price, boolean stale) {
    }
}
