package io.jethro.trading.algo.strategy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0052: the live {@link SignalParams} override drives the detector at runtime, while a null
 * source keeps the construction-time constants (the reproducible backtest path). Reuses the worked
 * example from {@link MomentumStrategyTest}: [100,100,100,100,102] with lookback 4 has z ≈ 1.0006.
 */
class MomentumStrategyLiveTest {

    private static List<TradeSignal> feed(MomentumStrategy s, String... prices) {
        List<TradeSignal> last = List.of();
        for (String p : prices) {
            last = s.evaluate(List.of(new MomentumStrategy.Observation("AAPL", new BigDecimal(p), false)));
        }
        return last;
    }

    /** A mutable SignalParams — stands in for the app's StrategyControl. */
    private static final class Params implements SignalParams {
        int lookback;
        double threshold;
        BigDecimal floor;
        double volumeConfirm;

        Params(int lookback, double threshold, BigDecimal floor, double volumeConfirm) {
            this.lookback = lookback;
            this.threshold = threshold;
            this.floor = floor;
            this.volumeConfirm = volumeConfirm;
        }

        @Override public int lookback() {
            return lookback;
        }

        @Override public double thresholdSigmas() {
            return threshold;
        }

        @Override public BigDecimal minSignalBps() {
            return floor;
        }

        @Override public double volumeConfirmMin() {
            return volumeConfirm;
        }
    }

    @Test
    void liveThresholdOverridesTheConstructionConstant() {
        // Construction threshold 1.2 would NOT fire on z ≈ 1.0; a live source lowering it to 0.95 does.
        var live = new Params(4, 0.95, new BigDecimal("2"), 0.0);
        var s = new MomentumStrategy(4, 1.2, new BigDecimal("2"), 0.0, live);
        assertEquals(1, feed(s, "100", "100", "100", "100", "102").size());
    }

    @Test
    void tighteningTheLiveThresholdSilencesTheSameMove() {
        var live = new Params(4, 1.2, new BigDecimal("2"), 0.0);
        var s = new MomentumStrategy(4, 0.95, new BigDecimal("2"), 0.0, live);
        assertTrue(feed(s, "100", "100", "100", "100", "102").isEmpty());
    }

    @Test
    void liveFloorGatesDustEvenWhenZClears() {
        // z clears an easy threshold, but a live 500bp floor discards the ~200bp move.
        var live = new Params(4, 0.5, new BigDecimal("500"), 0.0);
        var s = new MomentumStrategy(4, 0.5, new BigDecimal("0"), 0.0, live);
        assertTrue(feed(s, "100", "100", "100", "100", "102").isEmpty());
    }

    @Test
    void nullLiveKeepsConstructionConstants_reproducibleBacktestPath() {
        var tight = new MomentumStrategy(4, 1.2, new BigDecimal("2"), 0.0, null);
        assertTrue(feed(tight, "100", "100", "100", "100", "102").isEmpty());
        var loose = new MomentumStrategy(4, 0.95, new BigDecimal("2"), 0.0, null);
        assertEquals(1, feed(loose, "100", "100", "100", "100", "102").size());
    }

    @Test
    void aBadLiveValueFallsBackToTheConstructionConstant() {
        // A live threshold of 0 (unusable) must not wedge the detector — it falls back to 0.95 and fires.
        var live = new Params(4, 0.0, null, Double.NaN);
        var s = new MomentumStrategy(4, 0.95, new BigDecimal("2"), 0.0, live);
        assertEquals(1, feed(s, "100", "100", "100", "100", "102").size());
    }
}
