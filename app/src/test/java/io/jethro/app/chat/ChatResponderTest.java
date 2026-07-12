package io.jethro.app.chat;

import io.jethro.domain.BookId;
import io.jethro.domain.Fill;
import io.jethro.domain.InstrumentId;
import io.jethro.domain.Side;
import io.jethro.trading.riskpnl.InstrumentRef;
import io.jethro.trading.riskpnl.InstrumentRefSource;
import io.jethro.trading.riskpnl.RiskLimitEvaluator;
import io.jethro.trading.riskpnl.RiskLimitSource;
import io.jethro.trading.riskpnl.RiskLimits;
import io.jethro.trading.riskpnl.RiskProjection;
import io.jethro.uigateway.AttentionFeed;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deterministic chat answers — the numbers come from the projection, exact. */
class ChatResponderTest {

    private final InstrumentRefSource refs = id ->
            Optional.of(new InstrumentRef("AAPL", "EQUITY", "USD", new BigDecimal("1")));

    private ChatResponder responderWithApplePosition(RiskLimitSource limits) {
        var projection = new RiskProjection(refs);
        projection.applyFill(new Fill("f1", "o1", new BookId("ALPHA"), new InstrumentId("AAPL"),
                Side.BUY, new BigDecimal("100"), new BigDecimal("10"), Instant.EPOCH));
        projection.applyMark("AAPL", new BigDecimal("12"), 0); // unrealized 100*(12-10)=200, exposure 1200
        return new ChatResponder(projection, limits, new RiskLimitEvaluator(new BigDecimal("0.80")), new AttentionFeed());
    }

    @Test
    void pnlForABookReportsExactFigures() {
        var responder = responderWithApplePosition(book -> RiskLimits.none());
        String answer = responder.answer(new ChatIntent(ChatIntent.Kind.PNL, "ALPHA", null));
        assertTrue(answer.contains("ALPHA"), answer);
        assertTrue(answer.contains("$200.00"), answer);   // total = 0 realized + 200 unrealized
    }

    @Test
    void exposureForAnInstrument() {
        var responder = responderWithApplePosition(book -> RiskLimits.none());
        String answer = responder.answer(new ChatIntent(ChatIntent.Kind.EXPOSURE, null, "AAPL"));
        assertTrue(answer.contains("AAPL"), answer);
        assertTrue(answer.contains("$1,200.00"), answer); // 100 * 12
    }

    @Test
    void positionsListsTheHolding() {
        var responder = responderWithApplePosition(book -> RiskLimits.none());
        String answer = responder.answer(new ChatIntent(ChatIntent.Kind.POSITIONS, null, null));
        assertTrue(answer.contains("AAPL"), answer);
        assertTrue(answer.contains("(ALPHA)"), answer);
    }

    @Test
    void limitsReportsABreach() {
        // gross cap 1000; AAPL gross 1200 → breach
        var responder = responderWithApplePosition(book -> new RiskLimits(new BigDecimal("1000"), null, null));
        String answer = responder.answer(ChatIntent.of(ChatIntent.Kind.LIMITS));
        assertTrue(answer.toLowerCase().contains("gross exposure"), answer);
        assertTrue(answer.contains("ALPHA"), answer);
    }

    @Test
    void noBreachesReportsAllClear() {
        var responder = responderWithApplePosition(book -> RiskLimits.none());
        String answer = responder.answer(ChatIntent.of(ChatIntent.Kind.LIMITS));
        assertEquals("All books within their risk limits.", answer);
    }

    @Test
    void helpListsCapabilities() {
        var responder = responderWithApplePosition(book -> RiskLimits.none());
        String answer = responder.answer(ChatIntent.of(ChatIntent.Kind.HELP));
        assertTrue(answer.toLowerCase().contains("pnl"), answer);
        assertTrue(answer.toLowerCase().contains("positions"), answer);
    }
}
