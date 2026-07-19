package io.jethro.app.ai;

import io.jethro.trading.algo.inference.EmbeddingClient;
import io.jethro.trading.algo.inference.InferenceException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Embeddings share the inference gate: they run when the model is free, skip (throw, so the caller
 *  degrades to no-memory) when an inference holds it, and always release it. */
class SingleFlightEmbeddingClientTest {

    private static final EmbeddingClient INNER = new EmbeddingClient() {
        @Override public String modelId() { return "e"; }
        @Override public float[] embed(String text) { return new float[]{1, 2}; }
    };

    @Test
    void skipsWhileAnInferenceHoldsTheGateAndResumesAfter() {
        var gate = new OllamaGate();
        var sf = new SingleFlightEmbeddingClient(INNER, gate);
        assertArrayEquals(new float[]{1, 2}, sf.embed("x"), 0f); // gate free → runs

        assertTrue(gate.tryAcquire()); // an inference takes the gate
        assertThrows(InferenceException.class, () -> sf.embed("x"), "must skip, not pile up");
        gate.release();
        assertArrayEquals(new float[]{1, 2}, sf.embed("x"), 0f); // released → runs again
    }

    @Test
    void releasesTheGateAfterEachEmbed() {
        var gate = new OllamaGate();
        var sf = new SingleFlightEmbeddingClient(INNER, gate);
        sf.embed("a");
        sf.embed("b"); // would throw if the first call hadn't released
        assertTrue(gate.tryAcquire(), "gate free after embeds complete");
    }
}
