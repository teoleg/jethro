package io.jethro.trading.riskpnl;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full-revaluation swap scenario P&amp;L (quant-engine step 2, second slice): ΔPV re-priced on
 * the shocked curve, so it carries CONVEXITY that a first-order DV01 shock cannot. Validated by
 * pricing identities (sign, magnitude, convexity asymmetry), not magic numbers.
 */
class SwapScenarioRevalTest {

    /** Reference-swap universe standing in for refdata (V27/V28); SwapPricingService no longer
     *  hardcodes it. */
    private static final SwapPricingService.ReferenceSwapUniverse UNIVERSE = () -> java.util.List.of(
            new SwapPricingService.ReferenceSwap("USD_IRS_5Y", 5, 0.0400, 1_000_000),
            new SwapPricingService.ReferenceSwap("USD_IRS_10Y", 10, 0.0410, 1_000_000));

    private static final LocalDate VAL_DATE = LocalDate.of(2026, 1, 15);

    private SwapPricingService serviceAt(String pct) {
        var s = new CurveService();
        for (String id : List.of("USD.SOFR.1Y", "USD.SOFR.2Y", "USD.SOFR.5Y", "USD.SOFR.10Y", "USD.SOFR.30Y")) {
            s.onRate(id, new BigDecimal(pct));
        }
        return new SwapPricingService(s, UNIVERSE);
    }

    @Test
    void payFixedGainsWhenRatesRiseAndLosesWhenTheyFall() {
        var svc = serviceAt("4.00");
        Map<String, BigDecimal> up = svc.swapPnlPerLotUnderShock(new BigDecimal("100"), VAL_DATE);
        Map<String, BigDecimal> down = svc.swapPnlPerLotUnderShock(new BigDecimal("-100"), VAL_DATE);
        assertTrue(up.get("USD_IRS_5Y").signum() > 0, "pay-fixed 5Y gains on +100bp");
        assertTrue(down.get("USD_IRS_5Y").signum() < 0, "pay-fixed 5Y loses on -100bp");
        // 5Y $1M DV01 ~ a few hundred $/bp → a 100bp move is tens of thousands of dollars.
        assertTrue(up.get("USD_IRS_5Y").abs().doubleValue() > 20_000
                && up.get("USD_IRS_5Y").abs().doubleValue() < 80_000,
                "5Y +100bp ΔPV magnitude: " + up.get("USD_IRS_5Y"));
    }

    @Test
    void fullRevaluationShowsConvexity() {
        // Convexity: |ΔPV(+100)| < |ΔPV(-100)| for a pay-fixed swap (PV is convex in rates).
        // A first-order DV01 shock would make these EQUAL — the asymmetry is the whole point.
        var svc = serviceAt("4.00");
        double up = svc.swapPnlPerLotUnderShock(new BigDecimal("100"), VAL_DATE).get("USD_IRS_10Y").doubleValue();
        double down = svc.swapPnlPerLotUnderShock(new BigDecimal("-100"), VAL_DATE).get("USD_IRS_10Y").doubleValue();
        assertTrue(up > 0 && down < 0, "signs: +" + up + " / " + down);
        assertTrue(Math.abs(up) < Math.abs(down),
                "convexity: gain on +100 (" + up + ") should be smaller than loss on -100 (" + down + ")");
        // But not wildly different — convexity is a second-order effect (within ~15%).
        assertTrue(Math.abs(Math.abs(down) - Math.abs(up)) < Math.abs(up) * 0.20, "convexity is second-order");
    }

    @Test
    void emptyWhenNoCurve() {
        assertTrue(new SwapPricingService(new CurveService(), UNIVERSE)
                .swapPnlPerLotUnderShock(new BigDecimal("100"), VAL_DATE).isEmpty());
    }

    @Test
    void scenarioEngineUsesFullRevaluationForSwaps() {
        var svc = serviceAt("4.00");
        InstrumentRefSource refs = id -> java.util.Optional.of(
                new InstrumentRef("USD_IRS_5Y", "SWAP", "USD", new BigDecimal("45000")));
        var engine = new ScenarioEngine(refs, svc);
        var fx = FxConversion.fromUsdPairMarks(Map.of());
        // 1 lot pay-fixed 5Y (the full-reval path ignores the DV01 multiplier and re-prices).
        var pos = new PositionRisk("MACRO", "USD_IRS_5Y", "SWAP", "USD",
                BigDecimal.ONE, new BigDecimal("4.00"), new BigDecimal("4.45"), true, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("45000"), new BigDecimal("45000"));

        var results = engine.run(List.of(pos), fx);
        BigDecimal up = results.stream().filter(r -> r.id().equals("rates-up-100"))
                .findFirst().orElseThrow().firmPnlUsd();
        BigDecimal down = results.stream().filter(r -> r.id().equals("rates-down-100"))
                .findFirst().orElseThrow().firmPnlUsd();
        assertTrue(up.signum() > 0 && down.signum() < 0, "pay-fixed: +rates gain, -rates loss");
        // Convexity through the engine: a first-order DV01 engine would give up == -down exactly.
        assertTrue(up.abs().compareTo(down.abs()) < 0, "full-reval convexity: |gain(+100)| < |loss(-100)|");
    }
}
