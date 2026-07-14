package io.jethro.trading.riskpnl;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seasoned (trade-dated) swap valuation: real Strata pricing with the REMAINING schedule.
 * The signs and the roll-down are the finance content under test; Strata owns the exact
 * discounting arithmetic (bounds, not hand-recomputed decimals — same convention as the
 * other Strata-backed tests).
 */
class SeasonedSwapValuationTest {

    private static SwapPricingService pricerAt(String pct) {
        var curves = new CurveService();
        for (String id : List.of("USD.SOFR.1Y", "USD.SOFR.2Y", "USD.SOFR.5Y", "USD.SOFR.10Y", "USD.SOFR.30Y")) {
            curves.onRate(id, new BigDecimal(pct));
        }
        return new SwapPricingService(curves);
    }

    private static final LocalDate TRADE = LocalDate.of(2026, 1, 15);

    @Test
    void payerStruckBelowTheCurveIsInTheMoneyAndReceiverMirrors() {
        // Pay fixed 4.00% against a 5.00% curve on $1M: strongly positive PV; receiver of the
        // same trade is the exact mirror (same magnitude, opposite sign).
        var pricer = pricerAt("5.00");
        var payer = pricer.valueSeasoned(TRADE, 5, true, 0.04, 1_000_000, TRADE).orElseThrow();
        var receiver = pricer.valueSeasoned(TRADE, 5, false, 0.04, 1_000_000, TRADE).orElseThrow();
        assertTrue(payer.presentValue().doubleValue() > 10_000,
                "pay 4% vs 5% curve over 5y ≈ +1%/y annuity, got " + payer.presentValue());
        assertTrue(payer.presentValue().add(receiver.presentValue()).abs().doubleValue() < 1e-6,
                "receiver mirrors payer exactly");
        assertTrue(payer.dv01().doubleValue() > 0, "payer gains as rates rise");
    }

    @Test
    void rollDownShrinksDv01AsTheTradeAges() {
        var pricer = pricerAt("4.00");
        var fresh = pricer.valueSeasoned(TRADE, 5, true, 0.04, 1_000_000, TRADE).orElseThrow();
        var aged = pricer.valueSeasoned(TRADE, 5, true, 0.04, 1_000_000, TRADE.plusYears(3)).orElseThrow();
        assertTrue(aged.dv01().doubleValue() > 0 && aged.dv01().doubleValue() < fresh.dv01().doubleValue() * 0.55,
                "3 years into a 5y swap ≲ 2/5 of the annuity remains: fresh " + fresh.dv01()
                        + " vs aged " + aged.dv01());
    }

    @Test
    void maturedTradeCarriesNothingAndNoCurveMeansNoNumber() {
        var pricer = pricerAt("4.00");
        var matured = pricer.valueSeasoned(TRADE, 5, true, 0.04, 1_000_000, TRADE.plusYears(6)).orElseThrow();
        assertTrue(matured.presentValue().signum() == 0 && matured.dv01().signum() == 0);
        assertTrue(new SwapPricingService(new CurveService())
                        .valueSeasoned(TRADE, 5, true, 0.04, 1_000_000, TRADE).isEmpty(),
                "no curve → empty, never a guessed valuation");
    }
}
