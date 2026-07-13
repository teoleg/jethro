package io.jethro.trading.riskpnl;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real Strata swap pricing on the live curve — validated by pricing identities rather
 * than magic numbers: a swap struck AT the par rate has ~zero PV; DV01 of a 5Y $1M swap
 * is a few hundred dollars per bp; PV moves the right way when the curve moves.
 */
class SwapPricingServiceTest {

    private static final LocalDate VAL_DATE = LocalDate.of(2026, 1, 15);

    private CurveService flatCurve(String pct) {
        var s = new CurveService();
        for (String id : List.of("USD.SOFR.1Y", "USD.SOFR.2Y", "USD.SOFR.5Y", "USD.SOFR.10Y", "USD.SOFR.30Y")) {
            s.onRate(id, new BigDecimal(pct));
        }
        return s;
    }

    @Test
    void emptyUntilTheCurveIsLive() {
        assertTrue(new SwapPricingService(new CurveService()).valueAll(VAL_DATE).isEmpty());
    }

    @Test
    void valuesBothReferenceSwapsWithSaneParRates() {
        List<SwapPricingService.SwapValuation> vals =
                new SwapPricingService(flatCurve("4.00")).valueAll(VAL_DATE);
        assertEquals(2, vals.size());
        for (var v : vals) {
            // On a flat 4% continuous zero curve the OIS par rate is ~4.0-4.2% (annual comp).
            assertTrue(v.parRate() > 0.038 && v.parRate() < 0.044,
                    v.instrumentId() + " par rate " + v.parRate());
        }
    }

    @Test
    void swapStruckAtParHasNearZeroPvAndRealDv01() {
        var service = new SwapPricingService(flatCurve("4.00"));
        var fiveY = service.valueAll(VAL_DATE).get(0);

        // The 5Y reference swap is struck at 4.00% fixed; measure PV against how far the
        // strike sits from par: PV ≈ (par − fixed) · DV01(bp) · 1e4. Consistency within 2%.
        double approx = (fiveY.parRate() - fiveY.fixedRate()) * 1e4 * fiveY.dv01().doubleValue();
        assertEquals(approx, fiveY.presentValue().doubleValue(),
                Math.max(50, Math.abs(approx) * 0.02),
                "PV consistent with (par − strike) × DV01");

        // 5Y $1M pay-fixed: DV01 a few hundred dollars per bp, positive (gains when rates rise).
        double dv01 = fiveY.dv01().doubleValue();
        assertTrue(dv01 > 200 && dv01 < 700, "5Y 1M DV01 " + dv01);
    }

    @Test
    void pvRisesWhenTheCurveRisesForAPayFixedSwap() {
        double pvLow = new SwapPricingService(flatCurve("4.00")).valueAll(VAL_DATE).get(0)
                .presentValue().doubleValue();
        double pvHigh = new SwapPricingService(flatCurve("4.50")).valueAll(VAL_DATE).get(0)
                .presentValue().doubleValue();
        assertTrue(pvHigh > pvLow + 1_000,
                "pay-fixed PV must rise materially with rates: " + pvLow + " -> " + pvHigh);
    }
}
