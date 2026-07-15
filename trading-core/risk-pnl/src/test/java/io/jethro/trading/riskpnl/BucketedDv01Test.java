package io.jethro.trading.riskpnl;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bucketed (key-rate) DV01 via Strata parameter sensitivities (quant-engine step 4).
 * Validated by identities, not magic numbers: the buckets are a PARTITION of the same
 * sensitivity vector the total DV01 sums, so they must add back to it exactly (to the
 * money scale); the risk must sit at the trade's maturity nodes; signs follow pay/receive.
 */
class BucketedDv01Test {

    private static final LocalDate VAL_DATE = LocalDate.of(2026, 1, 15);

    private static CurveService flatCurve(String pct) {
        var s = new CurveService();
        for (String id : List.of("USD.SOFR.1Y", "USD.SOFR.2Y", "USD.SOFR.5Y", "USD.SOFR.10Y", "USD.SOFR.30Y")) {
            s.onRate(id, new BigDecimal(pct));
        }
        return s;
    }

    private static BigDecimal sum(List<SwapPricingService.TenorDv01> buckets) {
        return buckets.stream().map(SwapPricingService.TenorDv01::dv01)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void emptyUntilTheCurveIsLive() {
        assertTrue(new SwapPricingService(new CurveService())
                .bucketedDv01Seasoned(VAL_DATE, 5, true, 0.04, 1_000_000, VAL_DATE).isEmpty());
    }

    @Test
    void bucketsPartitionTheTotalDv01Exactly() {
        var service = new SwapPricingService(flatCurve("4.00"));
        var buckets = service.bucketedDv01Seasoned(VAL_DATE, 5, true, 0.04, 1_000_000, VAL_DATE)
                .orElseThrow();
        var total = service.valueSeasoned(VAL_DATE, 5, true, 0.04, 1_000_000, VAL_DATE)
                .orElseThrow().dv01();
        // Same sensitivity vector, partitioned vs summed — agree to a cent (per-node rounding).
        assertTrue(sum(buckets).subtract(total).abs().doubleValue() < 0.01,
                "buckets " + sum(buckets) + " vs total " + total);
    }

    @Test
    void fiveYearSwapRiskSitsAtTheFiveYearNode() {
        var buckets = new SwapPricingService(flatCurve("4.00"))
                .bucketedDv01Seasoned(VAL_DATE, 5, true, 0.04, 1_000_000, VAL_DATE).orElseThrow();
        assertEquals(List.of("1Y", "2Y", "5Y", "10Y", "30Y"),
                buckets.stream().map(SwapPricingService.TenorDv01::tenor).toList());

        var byTenor = buckets.stream().collect(java.util.stream.Collectors.toMap(
                SwapPricingService.TenorDv01::tenor, b -> b.dv01().doubleValue()));
        // Pay-fixed 5Y: dominant positive exposure at the 5Y node (discount+forecast risk
        // concentrates at maturity on a zero curve); nothing beyond it.
        assertTrue(byTenor.get("5Y") > 200, "5Y bucket " + byTenor.get("5Y"));
        assertTrue(byTenor.get("5Y") > byTenor.get("1Y") && byTenor.get("5Y") > byTenor.get("2Y"),
                "risk concentrates at maturity");
        assertEquals(0, byTenor.get("10Y"), 1.0, "no risk past maturity");
        assertEquals(0, byTenor.get("30Y"), 1.0, "no risk past maturity");
    }

    @Test
    void receiveFixedFlipsEveryMaterialBucketSign() {
        var service = new SwapPricingService(flatCurve("4.00"));
        var pay = service.bucketedDv01Seasoned(VAL_DATE, 10, true, 0.041, 1_000_000, VAL_DATE).orElseThrow();
        var receive = service.bucketedDv01Seasoned(VAL_DATE, 10, false, 0.041, 1_000_000, VAL_DATE).orElseThrow();
        for (int i = 0; i < pay.size(); i++) {
            assertEquals(pay.get(i).dv01().negate(), receive.get(i).dv01(),
                    "receive-fixed is the exact mirror at " + pay.get(i).tenor());
        }
    }

    @Test
    void agedTradeShiftsRiskDownTheCurve() {
        var service = new SwapPricingService(flatCurve("4.00"));
        // A 10Y traded 6 years ago has 4 years left: its risk belongs at/below the 5Y node.
        var aged = service.bucketedDv01Seasoned(VAL_DATE.minusYears(6), 10, true, 0.041,
                1_000_000, VAL_DATE).orElseThrow();
        var byTenor = aged.stream().collect(java.util.stream.Collectors.toMap(
                SwapPricingService.TenorDv01::tenor, b -> b.dv01().doubleValue()));
        assertEquals(0, byTenor.get("10Y"), 1.0, "roll-down: nothing left at 10Y");
        assertTrue(byTenor.get("5Y") > 100, "remaining risk near the 4y point: " + byTenor);
    }

    @Test
    void maturedTradeReportsZeroInEveryBucket() {
        var buckets = new SwapPricingService(flatCurve("4.00"))
                .bucketedDv01Seasoned(VAL_DATE.minusYears(6), 5, true, 0.04, 1_000_000, VAL_DATE)
                .orElseThrow();
        assertTrue(buckets.stream().allMatch(b -> b.dv01().signum() == 0));
    }

    @Test
    void keyTenorFollowsTheCtdWindowAndSixPercentRule() {
        // No curve: known futures default to the SHORT window end (below-6% world), disclosed.
        var noCurve = BondFutureDurations.staticOnly(id -> java.util.Optional.empty());
        assertEquals(1.75, noCurve.keyTenorYears("ZT").orElseThrow());
        assertEquals(6.5, noCurve.keyTenorYears("ZN").orElseThrow());
        assertTrue(noCurve.keyTenorYears("ES").isEmpty(), "not a bond future");

        // Live curve at 4.5% (< 6%): CTD stays the short end.
        var live = new BondFutureDurations(tsyCurveAt(4.5), id -> java.util.Optional.empty());
        assertEquals(15.0, live.keyTenorYears("ZB").orElseThrow());

        // At 7% (> 6%) the LONG end becomes cheapest-to-deliver.
        var inverted = new BondFutureDurations(tsyCurveAt(7.0), id -> java.util.Optional.empty());
        assertEquals(25.0, inverted.keyTenorYears("ZB").orElseThrow());
    }

    private static TreasuryCurveView tsyCurveAt(double pct) {
        var curve = new TreasuryCurveView();
        for (String id : List.of("USD.TSY.1Y", "USD.TSY.2Y", "USD.TSY.5Y", "USD.TSY.10Y", "USD.TSY.30Y")) {
            curve.onRate(id, BigDecimal.valueOf(pct));
        }
        return curve;
    }
}
