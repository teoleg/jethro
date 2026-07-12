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

    /** Static ref data: AAPL equity (mult 1), ES future (mult 50). */
    private final InstrumentRefSource refs = id -> Optional.ofNullable(Map.of(
            "AAPL", new InstrumentRef("AAPL", "EQUITY", "USD", new BigDecimal("1")),
            "ES", new InstrumentRef("ES", "FUTURE", "USD", new BigDecimal("50"))
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
}
