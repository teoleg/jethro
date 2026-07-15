package io.jethro.app.risk;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The one sizing formula both engines use: notional = riskBudget/σ_daily, per-class cap. */
class VolTargetingTest {

    private static void eq(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " got " + actual);
    }

    @Test
    void budgetOverVolWithCapWorkedExamples() {
        BigDecimal budget = new BigDecimal("250");
        // AAPL σ=1.8%/day → 250/0.018 = 13,888.88 (down): within the 50k cap.
        eq("13888.88", VolTargeting.notionalFor(budget, new BigDecimal("0.018"), new BigDecimal("50000")));
        // ZN σ=0.40%/day → 62,500: more notional to carry the same risk, within the 150k BOND cap.
        eq("62500.00", VolTargeting.notionalFor(budget, new BigDecimal("0.004"), new BigDecimal("150000")));
        // Near-zero vol would ask for 2.5M — the cap holds the line.
        eq("50000", VolTargeting.notionalFor(budget, new BigDecimal("0.0001"), new BigDecimal("50000")));
        // No cap configured → the raw vol-targeted figure.
        eq("2500000.00", VolTargeting.notionalFor(budget, new BigDecimal("0.0001"), null));
    }

    @Test
    void marginalSizingScalesByPortfolioCorrelationWithTheRhoFloor() {
        BigDecimal budget = new BigDecimal("250");
        BigDecimal vol = new BigDecimal("0.02"); // standalone: 250/0.02 = 12,500
        // ρ = 0.5: only half its vol adds to the book → 250/(0.02×0.5) = 25,000.
        eq("25000.00", VolTargeting.marginalNotionalFor(budget, vol, new BigDecimal("0.5"), new BigDecimal("100000")));
        // ρ = 1 (duplicates the book): exactly the standalone size.
        eq("12500.00", VolTargeting.marginalNotionalFor(budget, vol, BigDecimal.ONE, new BigDecimal("100000")));
        // ρ = 0.05 (near-diversifier) and ρ = −0.8 (hedge) both hit the 0.25 floor → 50,000 —
        // never super-sized on a correlation estimate.
        eq("50000.00", VolTargeting.marginalNotionalFor(budget, vol, new BigDecimal("0.05"), new BigDecimal("100000")));
        eq("50000.00", VolTargeting.marginalNotionalFor(budget, vol, new BigDecimal("-0.8"), new BigDecimal("100000")));
        // The per-class cap still holds the line.
        eq("30000", VolTargeting.marginalNotionalFor(budget, vol, new BigDecimal("0.1"), new BigDecimal("30000")));
    }
}
