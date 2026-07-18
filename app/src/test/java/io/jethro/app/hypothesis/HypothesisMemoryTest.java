package io.jethro.app.hypothesis;

import io.jethro.domain.Side;
import io.jethro.trading.algo.hypothesis.Hypothesis;
import io.jethro.trading.algo.hypothesis.NarrativeItem;
import io.jethro.trading.algo.inference.EmbeddingClient;
import io.jethro.trading.algo.inference.InferenceException;
import io.jethro.trading.algo.inference.SemanticMemory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Semantic hypothesis de-dup (ADR-0035): reworded same-story calls collapse; a different reason
 *  or the opposite direction fires; and any embedding failure degrades to "not a duplicate". */
class HypothesisMemoryTest {

    // A toy embedder: "beat" stories embed one way, "miss" another, else a third direction.
    // (EmbeddingClient has two abstract methods, so it's not a functional interface — no lambda.)
    private static final EmbeddingClient TOY = new EmbeddingClient() {
        @Override
        public String modelId() {
            return "toy";
        }

        @Override
        public float[] embed(String text) {
            if (text.contains("beat")) {
                return new float[]{1, 0, 0};
            }
            if (text.contains("miss")) {
                return new float[]{0, 1, 0};
            }
            return new float[]{0, 0, 1};
        }
    };

    private static Hypothesis h(String instrument, Side dir, String thesis) {
        return new Hypothesis(UUID.randomUUID().toString(), instrument, dir,
                Hypothesis.Horizon.SWING, Hypothesis.Conviction.MEDIUM, thesis, List.of());
    }

    private static HypothesisMemory memory(EmbeddingClient client) {
        return new HypothesisMemory(client, new SemanticMemory<>(100), new SemanticMemory<>(100), 0.9, 0.55);
    }

    @Test
    void rewordedSameStoryIsASemanticDuplicate() {
        var mem = memory(TOY);
        var first = h("AAPL", Side.BUY, "earnings beat drives upside");
        assertFalse(mem.isSemanticDuplicate(first));
        mem.remember(first);

        assertTrue(mem.isSemanticDuplicate(h("AAPL", Side.BUY, "a strong beat — stay long")),
                "same instrument+direction, same underlying story ⇒ semantic duplicate");
    }

    @Test
    void differentReasonOrDirectionFires() {
        var mem = memory(TOY);
        mem.remember(h("AAPL", Side.BUY, "earnings beat drives upside"));
        assertFalse(mem.isSemanticDuplicate(h("AAPL", Side.BUY, "guidance miss, fade it")),
                "a different story is not a duplicate");
        assertFalse(mem.isSemanticDuplicate(h("AAPL", Side.SELL, "a strong beat but overbought")),
                "the opposite direction is a distinct call");
        assertFalse(mem.isSemanticDuplicate(h("ES", Side.BUY, "a strong beat")),
                "a different instrument is out of scope");
    }

    @Test
    void embeddingFailureDegradesToNotDuplicate() {
        EmbeddingClient boom = new EmbeddingClient() {
            @Override
            public String modelId() {
                return "boom";
            }

            @Override
            public float[] embed(String text) {
                throw new InferenceException("embedding model unavailable");
            }
        };
        var mem = memory(boom);
        var call = h("AAPL", Side.BUY, "earnings beat");
        assertFalse(mem.isSemanticDuplicate(call), "a down embedder must never block a call");
        mem.remember(call); // must not throw
    }

    @Test
    void recallsPastOutcomesForRelatedNewsPerInstrument() {
        var mem = memory(TOY);
        mem.rememberOutcome("AAPL", "BUY", "earnings beat drives upside", "WIN", "1200");

        var news = List.of(new NarrativeItem("n1", 0, NarrativeItem.Category.EARNINGS,
                "AAPL", NarrativeItem.Sentiment.BULLISH, "AAPL beat expectations"));
        List<String> recalled = mem.recallSimilar(news);
        assertEquals(1, recalled.size());
        assertTrue(recalled.get(0).contains("WIN"), "the recalled digest carries the outcome");
        assertTrue(recalled.get(0).contains("AAPL"));
    }

    @Test
    void recallIgnoresOtherInstrumentsAndMacroNews() {
        var mem = memory(TOY);
        mem.rememberOutcome("AAPL", "BUY", "earnings beat", "WIN", "1200");

        // Same story text but a different instrument scope → no recall.
        assertTrue(mem.recallSimilar(List.of(new NarrativeItem("n2", 0, NarrativeItem.Category.NEWS,
                "ES", NarrativeItem.Sentiment.BULLISH, "ES beat"))).isEmpty());
        // Macro news has no instrument to scope on → skipped.
        assertTrue(mem.recallSimilar(List.of(new NarrativeItem("n3", 0, NarrativeItem.Category.MACRO,
                null, NarrativeItem.Sentiment.NEUTRAL, "CPI beat"))).isEmpty());
    }

    @Test
    void disabledMemoryIsANoOp() {
        assertFalse(HypothesisMemory.DISABLED.enabled());
        var call = h("AAPL", Side.BUY, "earnings beat");
        assertFalse(HypothesisMemory.DISABLED.isSemanticDuplicate(call));
        HypothesisMemory.DISABLED.remember(call); // must not throw
        HypothesisMemory.DISABLED.rememberOutcome("AAPL", "BUY", "beat", "WIN", "10"); // must not throw
        assertTrue(HypothesisMemory.DISABLED.recallSimilar(List.of(new NarrativeItem(
                "n", 0, NarrativeItem.Category.NEWS, "AAPL", NarrativeItem.Sentiment.BULLISH, "x"))).isEmpty());
    }
}
