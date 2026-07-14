package io.jethro.trading.riskpnl;

import io.jethro.domain.BookId;
import io.jethro.domain.Fill;
import io.jethro.domain.InstrumentId;
import io.jethro.domain.Side;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exact-value PnL/exposure tests (invariant 1 makes exact assertions possible — use it)
 * plus the duplicate-delivery idempotency test every consumer ships (invariant 6).
 */
class RiskProjectionTest {

    /** Static ref data: AAPL equity (mult 1), ES future (mult 50), SAP EUR equity. */
    private final InstrumentRefSource refs = id -> Optional.ofNullable(Map.of(
            "AAPL", new InstrumentRef("AAPL", "EQUITY", "USD", new BigDecimal("1")),
            "ES", new InstrumentRef("ES", "FUTURE", "USD", new BigDecimal("50")),
            "SAP", new InstrumentRef("SAP", "EQUITY", "EUR", new BigDecimal("1"))
    ).get(id));

    private static Fill fill(String id, String instrument, Side side, String qty, String price) {
        return new Fill(id, "ord-" + id, new BookId("ALPHA"), new InstrumentId(instrument),
                side, new BigDecimal(qty), new BigDecimal(price), Instant.EPOCH);
    }

    private static PositionRisk only(ConsolidatedRisk risk) {
        assertEquals(1, risk.positions().size(), "expected exactly one position");
        return risk.positions().get(0);
    }

    private static void eq(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " got " + actual);
    }

    @Test
    void longEquityUnrealizedAndExposureMarkToMarket() {
        var p = new RiskProjection(refs);
        p.applyFill(fill("f1", "AAPL", Side.BUY, "100", "10"));
        p.applyMark("AAPL", new BigDecimal("12.00"), 1_000);

        PositionRisk r = only(p.snapshot(1_000));
        eq("100", r.quantity());
        eq("10", r.avgCost());
        eq("0", r.realizedPnl());
        eq("200", r.unrealizedPnl());   // 100 * (12 - 10) * 1
        eq("1200", r.netExposure());    // 100 * 12 * 1
        eq("1200", r.grossExposure());
    }

    @Test
    void realizedPnlOnPartialCloseThenRemainderMarksToMarket() {
        var p = new RiskProjection(refs);
        p.applyFill(fill("f1", "AAPL", Side.BUY, "100", "10"));
        p.applyFill(fill("f2", "AAPL", Side.SELL, "40", "12"));  // realize (12-10)*40 = 80
        p.applyMark("AAPL", new BigDecimal("12"), 2_000);

        PositionRisk r = only(p.snapshot(2_000));
        eq("60", r.quantity());
        eq("80", r.realizedPnl());
        eq("120", r.unrealizedPnl());   // 60 * (12 - 10)
        eq("200", r.totalPnl());
    }

    @Test
    void contractMultiplierScalesFuturesPnlAndExposure() {
        var p = new RiskProjection(refs);
        p.applyFill(fill("f1", "ES", Side.BUY, "2", "100"));
        p.applyMark("ES", new BigDecimal("110"), 3_000);

        PositionRisk r = only(p.snapshot(3_000));
        eq("1000", r.unrealizedPnl());  // 2 * (110 - 100) * 50
        eq("11000", r.netExposure());   // 2 * 110 * 50
    }

    @Test
    void duplicateFillDeliveryLeavesStateUnchanged() {
        var p = new RiskProjection(refs);
        Fill f = fill("f1", "AAPL", Side.BUY, "100", "10");
        p.applyFill(f);
        p.applyFill(f);                 // redelivered — must be a no-op (invariant 6)
        p.applyMark("AAPL", new BigDecimal("12"), 4_000);

        PositionRisk r = only(p.snapshot(4_000));
        eq("100", r.quantity());        // not 200
        eq("200", r.unrealizedPnl());
    }

    @Test
    void unrealizedAndExposureAreZeroWithoutAMark() {
        var p = new RiskProjection(refs);
        p.applyFill(fill("f1", "AAPL", Side.BUY, "100", "10"));

        PositionRisk r = only(p.snapshot(5_000));
        assertTrue(!r.hasMark());
        eq("0", r.unrealizedPnl());
        eq("0", r.grossExposure());
        assertEquals(-1, r.markAgeMillis());
    }

    @Test
    void eurPositionConvertsToUsdInRollupsAtTheLiveFxMark() {
        // Cross-currency rollup (quant-engine phase 3): SAP long 100 @180 EUR, mark 190 EUR
        // → unrealized 1,000 EUR, gross 19,000 EUR. EURUSD mark 1.085 → rollups report USD:
        // unrealized 1,085.00, gross 20,615.00. The position row itself stays in EUR.
        var p = new RiskProjection(refs);
        p.applyFill(fill("f1", "SAP", Side.BUY, "100", "180"));
        p.applyMark("SAP", new BigDecimal("190"), 1_000);
        p.applyMark("EURUSD", new BigDecimal("1.085"), 1_000);

        ConsolidatedRisk risk = p.snapshot(1_000);
        PositionRisk row = only(risk);
        assertEquals("EUR", row.currency());
        eq("1000", row.unrealizedPnl());                       // instrument currency

        var book = risk.byBook().get(0);
        assertEquals("USD", book.currency(), "rollup reports in USD, not MIXED");
        eq("1085", book.unrealizedPnl());                      // 1000 × 1.085
        eq("20615", book.grossExposure());                     // 19000 × 1.085
        eq("1085", risk.total().unrealizedPnl());
    }

    @Test
    void unconvertibleCurrencyKeepsTheHonestMixedMarker() {
        // Same EUR position but NO EURUSD mark → the rollup must not silently mis-sum.
        var p = new RiskProjection(refs);
        p.applyFill(fill("f1", "SAP", Side.BUY, "100", "180"));
        p.applyMark("SAP", new BigDecimal("190"), 1_000);

        assertEquals("MIXED", p.snapshot(1_000).byBook().get(0).currency());
    }

    @Test
    void rollupsGroupByAssetClassAndBookAndTotalsAddUp() {
        var p = new RiskProjection(refs);
        p.applyFill(fill("f1", "AAPL", Side.BUY, "100", "10"));
        p.applyFill(fill("f2", "ES", Side.BUY, "2", "100"));
        p.applyMark("AAPL", new BigDecimal("12"), 6_000);   // unreal 200, exp 1200
        p.applyMark("ES", new BigDecimal("110"), 6_000);    // unreal 1000, exp 11000

        ConsolidatedRisk risk = p.snapshot(6_000);
        eq("1200", risk.total().unrealizedPnl());
        eq("12200", risk.total().grossExposure());

        assertEquals(2, risk.byAssetClass().size());
        var equity = risk.byAssetClass().stream().filter(g -> g.key().equals("EQUITY")).findFirst().orElseThrow();
        var future = risk.byAssetClass().stream().filter(g -> g.key().equals("FUTURE")).findFirst().orElseThrow();
        eq("200", equity.unrealizedPnl());
        eq("1000", future.unrealizedPnl());

        assertEquals(1, risk.byBook().size(), "both positions are in ALPHA");
        eq("12200", risk.byBook().get(0).grossExposure());
    }

    /** SWAP refdata per V9: mark = par rate in %, static multiplier = inception DV01 × 100. */
    private final InstrumentRefSource swapRefs = id -> Optional.ofNullable(Map.of(
            "USD_IRS_5Y", new InstrumentRef("USD_IRS_5Y", "SWAP", "USD", new BigDecimal("45000"))
    ).get(id));

    @Test
    void swapPnlUsesTheLiveDv01NotTheInceptionConstant() {
        // Live annuity fell as rates rose: DV01 now $430/bp per lot (was $450 at inception).
        SwapDv01Source live = id -> "USD_IRS_5Y".equals(id)
                ? Optional.of(new BigDecimal("430")) : Optional.empty();
        var p = new RiskProjection(swapRefs, live);
        p.applyFill(fill("f1", "USD_IRS_5Y", Side.BUY, "2", "4.04")); // pay fixed, 2 lots
        p.applyMark("USD_IRS_5Y", new BigDecimal("4.14"), 1_000);     // +10bp

        PositionRisk r = only(p.snapshot(1_000));
        // unrealized = 2 × (4.14 − 4.04) × (430 × 100) = 2 × 0.10 × 43,000 = 8,600
        // (the static 45,000 constant would overstate it as 9,000).
        eq("8600", r.unrealizedPnl());
    }

    @Test
    void swapPnlFallsBackToTheStaticMultiplierUntilTheCurvePrices() {
        var p = new RiskProjection(swapRefs, SwapDv01Source.NONE);
        p.applyFill(fill("f1", "USD_IRS_5Y", Side.BUY, "2", "4.04"));
        p.applyMark("USD_IRS_5Y", new BigDecimal("4.14"), 1_000);
        // V9 static convention: 2 × 0.10 × 45,000 = 9,000 — disclosed approximation, never zero.
        eq("9000", only(p.snapshot(1_000)).unrealizedPnl());
    }
}
