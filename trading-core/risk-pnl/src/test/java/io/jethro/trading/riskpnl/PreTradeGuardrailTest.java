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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pre-trade guardrail: reject an order that would push a book over its exposure cap. */
class PreTradeGuardrailTest {

    private final InstrumentRefSource refs = id -> Optional.ofNullable(Map.of(
            "AAPL", new InstrumentRef("AAPL", "EQUITY", "USD", new BigDecimal("1"))
    ).get(id));

    private RiskProjection projectionWithMark() {
        var p = new RiskProjection(refs);
        p.applyMark("AAPL", new BigDecimal("100"), 0); // 100/share
        return p;
    }

    // gross cap 10,000; no net cap
    private RiskLimitSource grossCap(String cap) {
        return book -> new RiskLimits(new BigDecimal(cap), null, null);
    }

    @Test
    void approvesAnOrderThatStaysWithinTheCap() {
        var p = projectionWithMark();
        var guardrail = new PreTradeGuardrail(p, grossCap("10000"));

        // buy 50 @100 → gross 5,000 ≤ 10,000
        assertTrue(guardrail.rejectionReason("ALPHA", "AAPL", new BigDecimal("50")).isEmpty());
    }

    @Test
    void approvesReachingExactlyTheCap() {
        var guardrail = new PreTradeGuardrail(projectionWithMark(), grossCap("10000"));
        // buy 100 @100 → gross exactly 10,000 → allowed
        assertTrue(guardrail.rejectionReason("ALPHA", "AAPL", new BigDecimal("100")).isEmpty());
    }

    @Test
    void rejectsAnOrderThatWouldExceedTheGrossCap() {
        var guardrail = new PreTradeGuardrail(projectionWithMark(), grossCap("10000"));
        // buy 101 @100 → gross 10,100 > 10,000 → reject
        Optional<String> reason = guardrail.rejectionReason("ALPHA", "AAPL", new BigDecimal("101"));
        assertTrue(reason.isPresent());
        assertTrue(reason.get().contains("gross exposure"), reason.get());
    }

    @Test
    void accountsForExistingPositionWhenProjecting() {
        var p = projectionWithMark();
        // already long 80 @100 → gross 8,000
        p.applyFill(new Fill("f1", "o1", new BookId("ALPHA"), new InstrumentId("AAPL"),
                Side.BUY, new BigDecimal("80"), new BigDecimal("100"), Instant.EPOCH));
        var guardrail = new PreTradeGuardrail(p, grossCap("10000"));

        // adding 30 → 110 @100 = 11,000 > 10,000 → reject
        assertTrue(guardrail.rejectionReason("ALPHA", "AAPL", new BigDecimal("30")).isPresent());
        // reducing by 30 → 50 @100 = 5,000 → approve (guardrail must not block risk-reducing trades)
        assertFalse(guardrail.rejectionReason("ALPHA", "AAPL", new BigDecimal("-30")).isPresent());
    }

    @Test
    void netCapUsesAbsoluteDirectionalExposure() {
        var p = projectionWithMark();
        RiskLimitSource netCap = book -> new RiskLimits(null, new BigDecimal("5000"), null);
        var guardrail = new PreTradeGuardrail(p, netCap);

        // short 60 @100 → net -6,000, |net| 6,000 > 5,000 → reject
        Optional<String> reason = guardrail.rejectionReason("ALPHA", "AAPL", new BigDecimal("-60"));
        assertTrue(reason.isPresent());
        assertTrue(reason.get().contains("net exposure"), reason.get());
    }

    @Test
    void noCapNeverRejects() {
        var guardrail = new PreTradeGuardrail(projectionWithMark(), book -> RiskLimits.none());
        assertTrue(guardrail.rejectionReason("ALPHA", "AAPL", new BigDecimal("100000")).isEmpty());
    }

    @Test
    void lossBreachedBookMayOnlyReduceRisk() {
        var p = projectionWithMark();
        // long 100 @100, mark drops to 80 → unrealized -2000; loss cap 1500 → breached
        p.applyFill(new Fill("f1", "o1", new BookId("ALPHA"), new InstrumentId("AAPL"),
                Side.BUY, new BigDecimal("100"), new BigDecimal("100"), Instant.EPOCH));
        p.applyMark("AAPL", new BigDecimal("80"), 0);
        RiskLimitSource lossCap = book -> new RiskLimits(null, null, new BigDecimal("1500"));
        var guardrail = new PreTradeGuardrail(p, lossCap);

        Optional<String> buyMore = guardrail.rejectionReason("ALPHA", "AAPL", new BigDecimal("10"));
        assertTrue(buyMore.isPresent(), "risk-adding order must be rejected over max loss");
        assertTrue(buyMore.get().contains("max loss"), buyMore.get());
        // reducing (selling down the long) is allowed so the book can flatten
        assertTrue(guardrail.rejectionReason("ALPHA", "AAPL", new BigDecimal("-50")).isEmpty());
    }

    @Test
    void sessionGateBlocksRiskAddingWhenClosedButAllowsFlattening() {
        var p = projectionWithMark();
        // already long 80 @100 → gross 8,000
        p.applyFill(new Fill("f1", "o1", new BookId("ALPHA"), new InstrumentId("AAPL"),
                Side.BUY, new BigDecimal("80"), new BigDecimal("100"), Instant.EPOCH));
        // huge cap so the SESSION gate is the only thing that can reject (ADR-0115)
        RiskLimitSource huge = grossCap("100000000");

        // session CLOSED: growing the long is refused …
        var closed = new PreTradeGuardrail(p, huge, () -> false);
        Optional<String> add = closed.rejectionReason("ALPHA", "AAPL", new BigDecimal("10"));
        assertTrue(add.isPresent());
        assertTrue(add.get().contains("session is closed"), add.get());
        // … but flattening is always allowed, so a stuck position can still be closed out-of-hours
        assertTrue(closed.rejectionReason("ALPHA", "AAPL", new BigDecimal("-10")).isEmpty());

        // session OPEN: the same risk-adding order is fine again
        var open = new PreTradeGuardrail(p, huge, () -> true);
        assertTrue(open.rejectionReason("ALPHA", "AAPL", new BigDecimal("10")).isEmpty());
    }

    @Test
    void perInstrumentConcentrationCapRejects() {
        var p = projectionWithMark();
        RiskLimitSource instrCap = book -> new RiskLimits(null, null, null, new BigDecimal("5000"));
        var guardrail = new PreTradeGuardrail(p, instrCap);

        // 60 @100 = 6000 > 5000 instrument cap → reject; 40 @100 = 4000 → fine
        Optional<String> reason = guardrail.rejectionReason("ALPHA", "AAPL", new BigDecimal("60"));
        assertTrue(reason.isPresent());
        assertTrue(reason.get().contains("per-instrument"), reason.get());
        assertTrue(guardrail.rejectionReason("ALPHA", "AAPL", new BigDecimal("40")).isEmpty());
    }

    @Test
    void firmCapAggregatesAcrossBooks() {
        var p = projectionWithMark();
        // BETA already long 80 @100 = 8000 firm gross
        p.applyFill(new Fill("f1", "o1", new BookId("BETA"), new InstrumentId("AAPL"),
                Side.BUY, new BigDecimal("80"), new BigDecimal("100"), Instant.EPOCH));
        RiskLimitSource firmCap = new RiskLimitSource() {
            @Override
            public RiskLimits limitsFor(String bookId) {
                return RiskLimits.none(); // no per-book caps — only the firm cap binds
            }

            @Override
            public RiskLimits firmLimits() {
                return new RiskLimits(new BigDecimal("10000"), null, null);
            }
        };
        var guardrail = new PreTradeGuardrail(p, firmCap);

        // ALPHA +30 @100 = 3000 → firm 11000 > 10000 → reject even though ALPHA itself is unlimited
        Optional<String> reason = guardrail.rejectionReason("ALPHA", "AAPL", new BigDecimal("30"));
        assertTrue(reason.isPresent());
        assertTrue(reason.get().contains("firm"), reason.get());
        assertTrue(guardrail.rejectionReason("ALPHA", "AAPL", new BigDecimal("20")).isEmpty());
    }

    @Test
    void reservationsCountInFlightOrdersBeforeTheirFillsProject() {
        var guardrail = new PreTradeGuardrail(projectionWithMark(), grossCap("10000"));

        // Order path (reserve=true): 60 @100 = 6000 approved and reserved. The projection
        // hasn't seen the fill yet, but a second 60 would take reserved+projected to 12000
        // > 10000 → rejected. Without reservations both would pass against stale exposure.
        assertTrue(guardrail.rejectionReason("ALPHA", "AAPL", new BigDecimal("60"), true).isEmpty());
        Optional<String> second = guardrail.rejectionReason("ALPHA", "AAPL", new BigDecimal("60"), true);
        assertTrue(second.isPresent(), "second in-flight order must count the first's reservation");
        // read-only checks (suggestions) never reserve
        assertTrue(guardrail.rejectionReason("ALPHA", "AAPL", new BigDecimal("30"), false).isEmpty());
    }
}
