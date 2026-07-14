package io.jethro.app.backtest;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The out-of-sample gate's math (ADR-0027): seeds disjoint from the live seed, and median
 * aggregation so "supported" means a MAJORITY of independent paths — one lucky path can't
 * carry the verdict.
 */
class BacktestServiceOosTest {

    private static BacktestResult run(BacktestResult.InstrumentResult... irs) {
        return new BacktestResult(0, 0, 0, 0, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0,
                BigDecimal.ZERO, List.of(irs), List.of());
    }

    private static BacktestResult.InstrumentResult ir(String id, int trades, String pnl) {
        return new BacktestResult.InstrumentResult(id, trades, new BigDecimal(pnl), BigDecimal.ZERO, BigDecimal.ZERO);
    }

    @Test
    void seedsAreDeterministicAndDisjointFromTheLiveSeed() {
        long live = 42;
        long[] a = BacktestService.oosSeeds(live, 5);
        long[] b = BacktestService.oosSeeds(live, 5);
        assertEquals(5, a.length);
        for (int i = 0; i < 5; i++) {
            assertEquals(a[i], b[i], "seed derivation must be deterministic");
            assertTrue(a[i] != live, "OOS seed " + i + " must differ from the live seed");
            for (int j = i + 1; j < 5; j++) {
                assertTrue(a[i] != a[j], "OOS seeds must be distinct");
            }
        }
    }

    @Test
    void medianVerdictNeedsAMajorityOfProfitablePaths() {
        // AAPL: profitable on 2 of 5 paths → median pnl NEGATIVE → not supported downstream.
        // MSFT: profitable on 3 of 5 → median POSITIVE → supported.
        Map<String, BacktestResult.InstrumentResult> agg = BacktestService.aggregateByMedian(List.of(
                run(ir("AAPL", 4, "500"), ir("MSFT", 3, "300")),
                run(ir("AAPL", 2, "-200"), ir("MSFT", 5, "-100")),
                run(ir("AAPL", 3, "-50"), ir("MSFT", 2, "250")),
                run(ir("AAPL", 1, "900"), ir("MSFT", 4, "80")),
                run(ir("AAPL", 6, "-400"), ir("MSFT", 1, "-30"))));

        // AAPL sorted pnls: -400, -200, -50, 500, 900 → median -50 (2-of-5 is not a majority).
        assertEquals(0, new BigDecimal("-50").compareTo(agg.get("AAPL").realizedPnl()));
        // MSFT sorted: -100, -30, 80, 250, 300 → median 80 (3-of-5 majority).
        assertEquals(0, new BigDecimal("80").compareTo(agg.get("MSFT").realizedPnl()));
        // Median trade count: AAPL 1,2,3,4,6 → 3; MSFT 1,2,3,4,5 → 3.
        assertEquals(3, agg.get("AAPL").trades());
        assertEquals(3, agg.get("MSFT").trades());
    }

    @Test
    void anInstrumentUntradedOnMostPathsShowsZeroMedianTrades() {
        // Traded on 2 of 5 paths → median trades 0 → downstream "no trades", never "supported".
        Map<String, BacktestResult.InstrumentResult> agg = BacktestService.aggregateByMedian(List.of(
                run(ir("SAP", 0, "0")), run(ir("SAP", 2, "40")), run(ir("SAP", 0, "0")),
                run(ir("SAP", 1, "20")), run(ir("SAP", 0, "0"))));
        assertEquals(0, agg.get("SAP").trades());
    }
}
