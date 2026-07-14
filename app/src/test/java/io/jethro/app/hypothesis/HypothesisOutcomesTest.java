package io.jethro.app.hypothesis;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Outcome scoring (ADR-0027), exact worked examples: pnl = (exit − entry) × qty × mult × dir. */
class HypothesisOutcomesTest {

    @Test
    void shortThatFellIsAWin() {
        // SHORT 143 GOOG, entry 175.00, exit 171.50 → (171.50−175.00)×143×1×(−1) = +500.50 WIN.
        var s = HypothesisOutcomes.score("SELL", new BigDecimal("175.00"), new BigDecimal("171.50"),
                new BigDecimal("143"), BigDecimal.ONE);
        assertEquals("WIN", s.outcome());
        assertEquals(0, new BigDecimal("500.50").compareTo(s.pnl()));
    }

    @Test
    void longThatFellIsALoss() {
        // LONG 131 AAPL, entry 190.00, exit 188.20 → (188.20−190.00)×131 = −235.80 LOSS.
        var s = HypothesisOutcomes.score("BUY", new BigDecimal("190.00"), new BigDecimal("188.20"),
                new BigDecimal("131"), BigDecimal.ONE);
        assertEquals("LOSS", s.outcome());
        assertEquals(0, new BigDecimal("-235.80").compareTo(s.pnl()));
    }

    @Test
    void multiplierScalesFuturesPnl() {
        // LONG 2 ES (mult 50), entry 5450.00, exit 5461.25 → 11.25×2×50 = +1125.00 WIN.
        var s = HypothesisOutcomes.score("BUY", new BigDecimal("5450.00"), new BigDecimal("5461.25"),
                new BigDecimal("2"), new BigDecimal("50"));
        assertEquals("WIN", s.outcome());
        assertEquals(0, new BigDecimal("1125.00").compareTo(s.pnl()));
    }

    @Test
    void unmovedMarkIsFlat() {
        var s = HypothesisOutcomes.score("BUY", new BigDecimal("100.00"), new BigDecimal("100.00"),
                new BigDecimal("10"), BigDecimal.ONE);
        assertEquals("FLAT", s.outcome());
        assertEquals(0, BigDecimal.ZERO.compareTo(s.pnl()));
    }
}
