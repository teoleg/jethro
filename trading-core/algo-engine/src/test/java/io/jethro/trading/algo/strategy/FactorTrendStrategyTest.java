package io.jethro.trading.algo.strategy;

import io.jethro.domain.Side;
import io.jethro.trading.algo.strategy.Strategy.Observation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Factor-level vol-gated trend follower (ADR-0070). Verifies the stance logic against synthetic tapes
 * that reproduce this sim's edge structure: LONG a calm up-trend, CUT (SELL) when factor vol spikes or
 * the factor trend turns down, HOLD in chop — all from observable prices, basket names only. Exact
 * BigDecimal prices; deterministic.
 */
class FactorTrendStrategyTest {

    private static final int TREND_WINDOW = 5;
    private static final int VOL_WINDOW = 6;
    private static final double TREND_THRESHOLD = 1.0;

    private static FactorTrendStrategy strategy(Set<String> basket) {
        return new FactorTrendStrategy(basket, TREND_WINDOW, VOL_WINDOW, TREND_THRESHOLD,
                new BigDecimal("1.5"), new BigDecimal("1.1"), 0.9);
    }

    /** Feed one evaluation cycle: id→price. */
    private static List<TradeSignal> cycle(FactorTrendStrategy s, Map<String, String> prices) {
        List<Observation> obs = new ArrayList<>();
        prices.forEach((id, p) -> obs.add(new Observation(id, new BigDecimal(p), false)));
        return s.evaluate(obs);
    }

    /** Feed the basket a common per-step growth factor for n cycles from p0; return the LAST cycle's signals. */
    private static List<TradeSignal> drive(FactorTrendStrategy s, List<String> ids, double p0,
                                           double stepFactor, int cycles) {
        List<TradeSignal> last = List.of();
        double price = p0;
        for (int i = 0; i < cycles; i++) {
            String px = String.format("%.6f", price);
            var m = new java.util.LinkedHashMap<String, String>();
            for (String id : ids) {
                m.put(id, px);
            }
            last = cycle(s, m);
            price *= stepFactor;
        }
        return last;
    }

    @Test
    void warmingUpEmitsNothing() {
        var s = strategy(Set.of("AAA", "BBB"));
        // Only 4 cycles → fewer than trendWindow factor steps → not warm → no stance.
        var sig = drive(s, List.of("AAA", "BBB"), 100.0, 1.001, 4);
        assertTrue(sig.isEmpty(), "must not fire before the trend window is warm");
    }

    @Test
    void calmUpTrendGoesLong() {
        var s = strategy(Set.of("AAA", "BBB"));
        var sig = drive(s, List.of("AAA", "BBB"), 100.0, 1.001, 10); // steady +0.1%/step, low vol
        assertFalse(sig.isEmpty(), "a warm calm up-trend must fire");
        assertTrue(sig.stream().allMatch(t -> t.side() == Side.BUY), "up-trend → BUY every basket name");
        assertEquals(Set.of("AAA", "BBB"),
                sig.stream().map(TradeSignal::instrumentId).collect(java.util.stream.Collectors.toSet()));
        assertTrue(sig.stream().allMatch(t -> "factor-trend".equals(t.kind())));
    }

    @Test
    void steadyDownTrendCuts() {
        var s = strategy(Set.of("AAA", "BBB"));
        var sig = drive(s, List.of("AAA", "BBB"), 100.0, 0.999, 10); // steady −0.1%/step, low vol
        assertFalse(sig.isEmpty(), "a warm down-trend must fire a cut");
        assertTrue(sig.stream().allMatch(t -> t.side() == Side.SELL), "down-trend → SELL (reduce to flat)");
    }

    @Test
    void aVolSpikeCutsEvenWithoutADownTrend() {
        var s = strategy(Set.of("AAA"));
        // Establish a CALM baseline with tiny steady steps (seeds vol baseline low).
        drive(s, List.of("AAA"), 100.0, 1.0002, 9);
        // Then a burst of large ALTERNATING moves: big |return| (vol ≫ baseline) but ~zero net trend.
        double[] burst = {1.05, 1 / 1.05, 1.05, 1 / 1.05, 1.05, 1 / 1.05};
        double price = 100.0 * Math.pow(1.0002, 9);
        List<TradeSignal> last = List.of();
        for (double f : burst) {
            price *= f;
            last = cycle(s, Map.of("AAA", String.format("%.6f", price)));
        }
        assertFalse(last.isEmpty(), "a vol spike must trigger a cut");
        assertTrue(last.stream().allMatch(t -> t.side() == Side.SELL), "elevated vol → SELL (de-risk)");
    }

    @Test
    void chopHolds() {
        var s = strategy(Set.of("AAA"));
        // Alternating equal-magnitude moves: net ≈ 0 (|z| < threshold), consistent magnitude (vol ≈ baseline).
        double price = 100.0;
        boolean up = true;
        List<TradeSignal> last = List.of();
        for (int i = 0; i < 16; i++) {
            price *= up ? 1.002 : 1 / 1.002;
            up = !up;
            last = cycle(s, Map.of("AAA", String.format("%.6f", price)));
        }
        assertTrue(last.isEmpty(), "chop with contained vol → HOLD, no signal");
    }

    @Test
    void nonBasketNamesAreIgnored() {
        var s = strategy(Set.of("AAA")); // FXX is NOT in the equity-factor basket
        double price = 100.0;
        List<TradeSignal> last = List.of();
        for (int i = 0; i < 10; i++) {
            String px = String.format("%.6f", price);
            last = cycle(s, Map.of("AAA", px, "FXX", px));
            price *= 1.001;
        }
        assertFalse(last.isEmpty());
        assertTrue(last.stream().noneMatch(t -> t.instrumentId().equals("FXX")),
                "a non-basket name must never be signalled by the equity-factor strategy");
    }

    @Test
    void emptyBasketIsANoOp() {
        var s = strategy(Set.of());
        var sig = drive(s, List.of("AAA", "BBB"), 100.0, 1.01, 12);
        assertTrue(sig.isEmpty(), "no equity basket → the strategy does nothing");
    }
}
