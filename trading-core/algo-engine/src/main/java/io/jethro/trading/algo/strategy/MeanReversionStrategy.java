package io.jethro.trading.algo.strategy;

import io.jethro.domain.Side;

import java.math.BigDecimal;
import java.util.List;

/**
 * Mean-reversion strategy (the second algo through the shared harness): the SAME
 * vol-adaptive z-score detector as {@link MomentumStrategy}, opposite conclusion — an
 * unusually large window move is faded, not followed (SELL the +3σ rip, BUY the −3σ dip).
 * Momentum and mean reversion cannot both be right on the same tape at the same horizon;
 * the out-of-sample harness (ADR-0027) adjudicates with measured, cost-honest medians —
 * that is the point of running a second strategy through it. Typically configured with a
 * WIDER threshold than momentum (fade only real extremes, e.g. 3σ+); in a long-only book
 * the SELL side only reduces existing longs (the standing guard applies unchanged).
 */
public final class MeanReversionStrategy implements Strategy {

    private final MomentumStrategy detector; // same windows, same z — one detector, two readings

    public MeanReversionStrategy(int lookback, double thresholdSigmas, BigDecimal minSignalBps) {
        this(lookback, thresholdSigmas, minSignalBps, 0.0);
    }

    /** With volume confirmation (ADR-0033): fade only extremes the market participated in. */
    public MeanReversionStrategy(int lookback, double thresholdSigmas, BigDecimal minSignalBps,
                                 double volumeConfirmMin) {
        this.detector = new MomentumStrategy(lookback, thresholdSigmas, minSignalBps, volumeConfirmMin);
    }

    @Override
    public List<TradeSignal> evaluate(List<Observation> observations) {
        return detector.evaluate(observations).stream()
                .map(s -> new TradeSignal(s.instrumentId(),
                        s.side() == Side.BUY ? Side.SELL : Side.BUY,
                        s.referencePrice(), s.price(), s.changeBps(), s.zScore(), s.baselineSigmas(), name()))
                .toList();
    }

    @Override
    public String name() {
        return "mean-reversion";
    }
}
