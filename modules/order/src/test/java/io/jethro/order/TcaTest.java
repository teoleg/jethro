package io.jethro.order;

import io.jethro.domain.Side;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Implementation-shortfall slippage vs arrival (ADR-0025 TCA) — exact worked examples. */
class TcaTest {

    private static void eq(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " got " + actual);
    }

    @Test
    void immediateMarketFillMeasuresBackTheModelledCost() {
        // BUY arrival 190.00, fill 190.066505 (2.5bp half-spread + 1bp fee):
        // (0.066505 / 190) × 10⁴ = 3.50026315... → 3.5003 (scale 4, HALF_EVEN).
        eq("3.5003", Tca.slippageBps(Side.BUY, new BigDecimal("190.00"),
                new BigDecimal("190.066505"), false));
        // SELL mirror: fill 189.933495 below arrival → same positive cost.
        eq("3.5003", Tca.slippageBps(Side.SELL, new BigDecimal("190.00"),
                new BigDecimal("189.933495"), false));
    }

    @Test
    void restingLimitThatGotItsPriceIsAnImprovement() {
        // BUY LIMIT 150 submitted when the market showed 151 → filled at 150:
        // (150 − 151)/151 × 10⁴ = −66.2252 bps — negative = improvement, not cost.
        eq("-66.2252", Tca.slippageBps(Side.BUY, new BigDecimal("151.00"),
                new BigDecimal("150.00"), false));
    }

    @Test
    void rateQuotedSlippageIsAdditiveBpOfRate() {
        // Pay-fixed (BUY) arrival par 4.0400, filled 4.0420 → (0.002) × 100 = 0.2 bp of rate.
        eq("0.2000", Tca.slippageBps(Side.BUY, new BigDecimal("4.0400"),
                new BigDecimal("4.0420"), true));
        // Receive-fixed (SELL) filled below arrival par → also a cost, same magnitude.
        eq("0.2000", Tca.slippageBps(Side.SELL, new BigDecimal("4.0400"),
                new BigDecimal("4.0380"), true));
    }
}
