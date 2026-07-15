package io.jethro.app.risk;

import io.jethro.domain.BookId;
import io.jethro.domain.Fill;
import io.jethro.domain.InstrumentId;
import io.jethro.domain.Side;
import io.jethro.trading.riskpnl.BondFutureDurations;
import io.jethro.trading.riskpnl.CurveService;
import io.jethro.trading.riskpnl.InstrumentRef;
import io.jethro.trading.riskpnl.InstrumentRefSource;
import io.jethro.trading.riskpnl.RiskProjection;
import io.jethro.trading.riskpnl.SwapPricingService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bucketed DV01 per book (quant-engine step 4) — exact worked example for the futures
 * key-rate split (finance-math rule) and the partition identity for the swap legs.
 */
class Dv01ServiceTest {

    private static final LocalDate VAL_DATE = LocalDate.of(2026, 1, 15);

    /** ZN: 10Y Treasury future, $1000 multiplier, static refdata duration 6.3. */
    private static final InstrumentRefSource REFS = id -> "ZN".equals(id)
            ? Optional.of(new InstrumentRef("ZN", "FUTURE", "USD",
                    new BigDecimal("1000"), new BigDecimal("6.3")))
            : Optional.empty();

    /** Stands in for the refdata tenor_years attribute (V27); USD_IRS_7Y is deliberately unknown. */
    private static final SwapTenorSource TENORS = id -> switch (id) {
        case "USD_IRS_5Y" -> Optional.of(5);
        case "USD_IRS_10Y" -> Optional.of(10);
        default -> Optional.empty();
    };

    private static CurveService flatSofr(String pct) {
        var s = new CurveService();
        for (String id : List.of("USD.SOFR.1Y", "USD.SOFR.2Y", "USD.SOFR.5Y", "USD.SOFR.10Y", "USD.SOFR.30Y")) {
            s.onRate(id, new BigDecimal(pct));
        }
        return s;
    }

    @Test
    void treasuryFutureDv01SplitsAcrossTheBracketingNodesExactly() {
        // Long 2 ZN @ mark 110.50, mult 1000 → netExposure = 221,000. Static duration 6.3:
        //   DV01 = −221,000 × 6.3 × 1e-4 = −139.23  (long futures lose when rates rise).
        // No live TSY curve → CTD defaults to ZN's SHORT window end, 6.5y, which sits
        // between the 5Y and 10Y nodes: w(10Y) = (6.5−5)/(10−5) = 0.3 exactly, so
        //   10Y bucket = −139.23 × 0.3 = −41.769;  5Y bucket = remainder = −97.461.
        var projection = new RiskProjection(REFS);
        projection.applyFill(new Fill("f1", "ord-1", new BookId("MACRO"), new InstrumentId("ZN"),
                Side.BUY, new BigDecimal("2"), new BigDecimal("110"), Instant.EPOCH));
        projection.applyMark("ZN", new BigDecimal("110.50"), 1_000);

        var service = new Dv01Service(projection, new SwapPricingService(new CurveService()),
                BondFutureDurations.staticOnly(REFS), Dv01Service.SwapTradeSource.NONE,
                TENORS, () -> VAL_DATE);
        var view = service.view(1_000);

        assertEquals(List.of("1Y", "2Y", "5Y", "10Y", "30Y"), view.tenors());
        assertEquals(1, view.books().size());
        var macro = view.books().get(0);
        assertEquals("MACRO", macro.book());
        eq("-97.461", macro.buckets().get("5Y"));
        eq("-41.769", macro.buckets().get("10Y"));
        eq("0", macro.buckets().get("1Y"));
        eq("-139.23", macro.total());
        eq("-139.23", view.firm().total());
        assertEquals(0, view.skipped());
    }

    @Test
    void swapBucketsMatchTheTradeDatedValuationIdentity() {
        Dv01Service.SwapTradeSource trades = () -> List.of(
                new Dv01Service.SwapTradeSource.Trade("MACRO", "USD_IRS_5Y", "BUY",
                        new BigDecimal("2"), new BigDecimal("4.00"), VAL_DATE));
        var pricer = new SwapPricingService(flatSofr("4.00"));
        var service = new Dv01Service(new RiskProjection(REFS), pricer,
                BondFutureDurations.staticOnly(REFS), trades, TENORS, () -> VAL_DATE);

        var view = service.view(1_000);
        assertTrue(view.curveLive());
        var macro = view.books().get(0);

        // The bucket row is the same Strata sensitivity vector the total DV01 sums —
        // it must reproduce valueSeasoned's figure (2 lots = $2M notional) to a cent.
        var total = pricer.valueSeasoned(VAL_DATE, 5, true, 0.04, 2_000_000, VAL_DATE)
                .orElseThrow().dv01();
        assertTrue(macro.total().subtract(total).abs().doubleValue() < 0.01,
                "buckets " + macro.total() + " vs seasoned total " + total);
        // Pay-fixed 5Y: positive risk, nothing past maturity.
        assertTrue(macro.buckets().get("5Y").doubleValue() > 400, "2 lots ≈ 2× the 1M DV01");
        assertEquals(0, macro.buckets().get("30Y").doubleValue(), 1.0);
    }

    @Test
    void noCurveIsDisclosedNotZeroed() {
        Dv01Service.SwapTradeSource trades = () -> List.of(
                new Dv01Service.SwapTradeSource.Trade("MACRO", "USD_IRS_5Y", "BUY",
                        BigDecimal.ONE, new BigDecimal("4.00"), VAL_DATE));
        var service = new Dv01Service(new RiskProjection(REFS),
                new SwapPricingService(new CurveService()),  // nothing quoted
                BondFutureDurations.staticOnly(REFS), trades, TENORS, () -> VAL_DATE);
        assertTrue(!service.view(1_000).curveLive(), "swap legs unvalued must be disclosed");
    }

    @Test
    void unknownSwapProductAndMarklessFutureAreSkippedAndCounted() {
        Dv01Service.SwapTradeSource trades = () -> List.of(
                new Dv01Service.SwapTradeSource.Trade("MACRO", "USD_IRS_7Y", "BUY",
                        BigDecimal.ONE, new BigDecimal("4.00"), VAL_DATE));
        var projection = new RiskProjection(REFS);
        projection.applyFill(new Fill("f1", "ord-1", new BookId("MACRO"), new InstrumentId("ZN"),
                Side.BUY, new BigDecimal("2"), new BigDecimal("110"), Instant.EPOCH));
        // no mark for ZN
        var service = new Dv01Service(projection, new SwapPricingService(flatSofr("4.00")),
                BondFutureDurations.staticOnly(REFS), trades, TENORS, () -> VAL_DATE);
        assertEquals(2, service.view(1_000).skipped(), "never silently dropped");
    }

    private static void eq(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "expected " + expected + " got " + actual);
    }
}
