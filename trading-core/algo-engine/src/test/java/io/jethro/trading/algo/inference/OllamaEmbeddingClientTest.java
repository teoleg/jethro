package io.jethro.trading.algo.inference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Parsing of Ollama's embedding response (the transport itself is exercised by FullStackIT). */
class OllamaEmbeddingClientTest {

    @Test
    void parsesEmbeddingVector() {
        float[] v = OllamaEmbeddingClient.parseEmbedding("{\"embedding\":[0.1,0.2,-0.3]}");
        assertEquals(3, v.length);
        assertEquals(0.1f, v[0], 1e-6);
        assertEquals(-0.3f, v[2], 1e-6);
    }

    @Test
    void rejectsMissingOrEmptyVector() {
        assertThrows(InferenceException.class, () -> OllamaEmbeddingClient.parseEmbedding("{}"));
        assertThrows(InferenceException.class, () -> OllamaEmbeddingClient.parseEmbedding("{\"embedding\":[]}"));
    }
}
