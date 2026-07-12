package io.jethro.trading.algo.agent;

import io.jethro.messaging.AiDecision;
import io.jethro.trading.algo.inference.InferenceRequest;
import io.jethro.trading.algo.inference.InferenceResult;
import io.jethro.trading.algo.inference.ModelInferenceClient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskCommentatorTest {

    private static final class FakeClient implements ModelInferenceClient {
        InferenceRequest lastRequest;

        @Override
        public String modelId() {
            return "fake-3b";
        }

        @Override
        public InferenceResult complete(InferenceRequest request) {
            this.lastRequest = request;
            return new InferenceResult("Feed healthy; AAPL near 101.", "fake-3b", 42, 100, 12);
        }
    }

    private static MarketView view() {
        return new MarketView(
                List.of(new MarketView.InstrumentMark("AAPL", new BigDecimal("101.230000"), false, 120),
                        new MarketView.InstrumentMark("MSFT", new BigDecimal("99.870000"), true, 60_000)),
                1234, 0);
    }

    @Test
    void recordsFullAuditDecision() {
        var client = new FakeClient();
        var recorded = new AtomicReference<AiDecision>();
        var commentator = new RiskCommentator(client, recorded::set, 128);

        String commentary = commentator.commentOn(view());

        assertEquals("Feed healthy; AAPL near 101.", commentary);
        AiDecision decision = recorded.get();
        assertNotNull(decision, "every run must record an AiDecision (invariant 7)");
        assertEquals("fake-3b", decision.getModelId());
        assertEquals(42, decision.getLatencyMillis());
        assertEquals(100, decision.getInputTokens());
        assertEquals(12, decision.getOutputTokens());
        assertEquals(64, decision.getContextHash().length(), "sha-256 hex expected");
        assertTrue(decision.getContextSnapshotJson().contains("\"price\":\"101.230000\""),
                "context snapshot must embed the exact computed data");
        assertTrue(decision.getContextSnapshotJson().contains("\"stale\":true"));
        assertTrue(decision.getProposedActionsJson().contains("commentary"));
        assertNotNull(decision.getMeta().getEventId());
    }

    @Test
    void contextHashIsStableForIdenticalViews() {
        var client = new FakeClient();
        var first = new AtomicReference<AiDecision>();
        var second = new AtomicReference<AiDecision>();
        new RiskCommentator(client, first::set, 128).commentOn(view());
        new RiskCommentator(client, second::set, 128).commentOn(view());
        assertEquals(first.get().getContextHash(), second.get().getContextHash(),
                "same computed context must hash identically (replay/audit)");
    }

    @Test
    void promptCarriesTheDataNotInventedNumbers() {
        var client = new FakeClient();
        new RiskCommentator(client, d -> { }, 128).commentOn(view());
        assertTrue(client.lastRequest.userPrompt().contains("101.230000"));
        assertTrue(client.lastRequest.userPrompt().contains("ticksIn"));
        assertTrue(client.lastRequest.systemPrompt().contains("Never invent numbers"));
    }
}
