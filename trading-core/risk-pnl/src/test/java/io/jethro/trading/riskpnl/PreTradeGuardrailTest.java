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
}
