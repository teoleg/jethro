package io.jethro.trading.riskpnl;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Historical-simulation VaR (ADR-0027), exact worked examples. Convention under test:
 * VaR_α = −pnl[⌊α·K⌋] of the ascending-sorted revaluation vector; ES_α = −mean of the tail
 * at or below that index.
 */
class VarMathTest {

    private static VarMath.DayVector day(int i, Map<String, Double> returns) {
        return new VarMath.DayVector(LocalDate.of(2026, 1, 1).plusDays(i), returns);
    }

    @Test
    void worksThroughTheTwentyDayExampleExactly() {
        // $100,000 long one instrument; 20 days: one −3%, one −2%, nine −1%, nine +1%.
        // pnl sorted: −3000, −2000, −1000×9, +1000×9.
        // VaR95: idx ⌊0.05·20⌋ = 1 → −(−2000) = 2000. ES95 = −mean(−3000, −2000) = 2500.
        // VaR99: idx 0 → 3000.
        List<VarMath.DayVector> days = new ArrayList<>();
        days.add(day(0, Map.of("AAPL", -0.03)));
        days.add(day(1, Map.of("AAPL", -0.02)));
        for (int i = 0; i < 9; i++) {
            days.add(day(2 + i, Map.of("AAPL", -0.01)));
            days.add(day(11 + i, Map.of("AAPL", 0.01)));
        }
        var r = VarMath.historicalVar(Map.of("AAPL", new BigDecimal("100000")), days, 20);

        assertEquals(0, new BigDecimal("2000.00").compareTo(r.var95()), "VaR95");
        assertEquals(0, new BigDecimal("2500.00").compareTo(r.es95()), "ES95");
        assertEquals(0, new BigDecimal("3000.00").compareTo(r.var99()), "VaR99");
        assertEquals(20, r.observations());
        assertEquals(0, new BigDecimal("100000.00").compareTo(r.coveredExposure()));
    }

    @Test
    void aPerfectHedgeHasZeroVar() {
        // Long 100k A, short 100k B, identical returns → every day's pnl is exactly 0.
        // THIS is what notional caps can't see: gross is 200k, risk is nil.
        List<VarMath.DayVector> days = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            double r = (i % 2 == 0 ? 1 : -1) * 0.02;
            days.add(day(i, Map.of("ES", r, "NQ", r)));
        }
        var r = VarMath.historicalVar(
                Map.of("ES", new BigDecimal("100000"), "NQ", new BigDecimal("-100000")), days, 20);
        assertEquals(0, BigDecimal.ZERO.compareTo(r.var95()), "hedged book → zero VaR");
    }

    @Test
    void insufficientHistoryIsHonestNotZeroRisk() {
        var r = VarMath.historicalVar(Map.of("AAPL", new BigDecimal("100000")),
                List.of(day(0, Map.of("AAPL", -0.01))), 20);
        assertEquals(1, r.observations());
        assertTrue(r.note().contains("insufficient history"), "must SAY it can't measure yet");
    }

    @Test
    void anInstrumentWithTooLittleHistoryIsSkippedAndCounted() {
        List<VarMath.DayVector> days = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            // SAP present on only 19 of 20 days → below minObs (20) → excluded, its |exposure| disclosed.
            days.add(day(i, i == 0 ? Map.of("AAPL", -0.01) : Map.of("AAPL", -0.01, "SAP", 0.01)));
        }
        var r = VarMath.historicalVar(
                Map.of("AAPL", new BigDecimal("100000"), "SAP", new BigDecimal("50000")), days, 20);
        assertEquals(0, new BigDecimal("100000.00").compareTo(r.coveredExposure()));
        assertEquals(0, new BigDecimal("50000.00").compareTo(r.skippedExposure()),
                "too-thin history is DISCLOSED, never silently riskless");
    }

    @Test
    void aGappyNameWithEnoughHistoryIsIncludedOnTheCommonWindow() {
        // ADR-0058 regression: the exact 2-day-live bug — an equity with ample history but a short
        // sim-contaminated tail gap must NOT be dropped from VaR (which had left it on AUDUSD alone).
        List<VarMath.DayVector> days = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            // MSFT present on 28 of 30 days (missing the last two "sim-tail" days); AUDUSD on all 30.
            days.add(i >= 28 ? day(i, Map.of("AUDUSD", 0.001))
                             : day(i, Map.of("AUDUSD", 0.001, "MSFT", -0.01)));
        }
        var r = VarMath.historicalVar(
                Map.of("AUDUSD", new BigDecimal("10000"), "MSFT", new BigDecimal("50000")), days, 20);
        assertEquals(0, new BigDecimal("60000.00").compareTo(r.coveredExposure()),
                "MSFT (28 ≥ 20 days) is included, not skipped for a 2-day gap");
        assertEquals(0, BigDecimal.ZERO.compareTo(r.skippedExposure()));
        assertEquals(28, r.observations(), "revalued on the 28 common days — the gappy tail drops, not MSFT");
    }
}
