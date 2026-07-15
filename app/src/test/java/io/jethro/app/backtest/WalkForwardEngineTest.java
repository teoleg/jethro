package io.jethro.app.backtest;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Walk-forward mechanics on synthetic bars (the engine under test is the SPLITTING and the
 * accounting; real data comes from fetch_bars.py on the host). Fold windows must be exact,
 * eval must run on frozen parameters over unseen days, and costs must bite.
 */
class WalkForwardEngineTest {

    /** 400 synthetic daily bars: a steady +0.4%/day up-trend — momentum's best case. */
    private static Map<String, List<HistoricalBars.Bar>> trendingBars() {
        List<HistoricalBars.Bar> bars = new java.util.ArrayList<>();
        BigDecimal price = new BigDecimal("100.00");
        LocalDate day = LocalDate.of(2024, 1, 1);
        for (int i = 0; i < 400; i++) {
            bars.add(new HistoricalBars.Bar(day, price));
            price = price.multiply(new BigDecimal("1.004")).setScale(6, java.math.RoundingMode.HALF_EVEN);
            day = day.plusDays(1);
        }
        return Map.of("AAPL", bars);
    }

    private static WalkForwardEngine.Config config(String algo, String costBps) {
        return new WalkForwardEngine.Config(algo, 100, 50,
                new BigDecimal("2"), new BigDecimal("10000"),
                new BigDecimal("20000"), new BigDecimal("30000"),
                false, new BigDecimal(costBps), Map.of("AAPL", BigDecimal.ONE));
    }

    @Test
    void foldWindowsRollExactlyAndReportTheirDates() {
        var result = new WalkForwardEngine().run(trendingBars(), config("momentum", "0"));
        // Axis 400 days, warm-up 49 (max lookback 48 + 1): folds start at index 49 and step by
        // 50 while start + 100 + 50 <= 400 → starts 49,99,149,199,249 = 5 folds.
        assertEquals(5, result.folds().size(), "fold arithmetic");
        var first = result.folds().get(0);
        assertEquals(LocalDate.of(2024, 1, 1).plusDays(49), first.fitStart());
        assertEquals(LocalDate.of(2024, 1, 1).plusDays(149), first.evalStart());
        assertEquals(LocalDate.of(2024, 1, 1).plusDays(198), first.evalEnd());
    }

    @Test
    void momentumIsSupportedOnAPureTrendAndMeanReversionIsNot() {
        var momentum = new WalkForwardEngine().run(trendingBars(), config("momentum", "3.5"));
        assertTrue(momentum.supported(), "a steady trend is momentum's best case");
        assertTrue(momentum.totalOosPnl().signum() > 0);
        assertTrue(momentum.folds().stream().allMatch(f -> f.oosTrades() > 0), "it actually traded");

        // Mean reversion fades the trend; long-only means SELL signals have nothing to reduce
        // and it simply never gets long — no trades, so it must NOT be "supported".
        var meanReversion = new WalkForwardEngine().run(trendingBars(), config("mean-reversion", "3.5"));
        assertTrue(!meanReversion.supported(), "fading a pure trend can't earn support");
    }

    @Test
    void costsBiteAndInsufficientHistoryIsHonest() {
        var free = new WalkForwardEngine().run(trendingBars(), config("momentum", "0"));
        var costly = new WalkForwardEngine().run(trendingBars(), config("momentum", "50"));
        assertTrue(costly.totalOosPnl().compareTo(free.totalOosPnl()) < 0,
                "50bp per fill must reduce OOS P&L");

        var tiny = Map.of("AAPL", trendingBars().get("AAPL").subList(0, 120));
        var result = new WalkForwardEngine().run(tiny, config("momentum", "0"));
        assertEquals(0, result.folds().size());
        assertTrue(!result.supported() && result.note() != null, "no folds → honest note, never supported");
    }
}
