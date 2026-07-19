package io.jethro.app.ai;

import io.jethro.trading.algo.inference.EmbeddingClient;
import io.jethro.trading.algo.inference.InferenceException;

/**
 * Serializes embedding calls through the shared {@link OllamaGate} (ADR-0035 / ADR-0016), so RAG
 * retrieval can never overlap a generate call on the single-model box — the collision that showed
 * up as "ollama unreachable" once RAG was enabled. If the model is busy the embed is skipped
 * (throws, which the caller treats as best-effort "no memory this cycle") rather than piling up.
 */
public final class SingleFlightEmbeddingClient implements EmbeddingClient {

    private final EmbeddingClient delegate;
    private final OllamaGate gate;

    public SingleFlightEmbeddingClient(EmbeddingClient delegate, OllamaGate gate) {
        this.delegate = delegate;
        this.gate = gate;
    }

    @Override
    public String modelId() {
        return delegate.modelId();
    }

    @Override
    public float[] embed(String text) {
        if (!gate.tryAcquire()) {
            throw new InferenceException("skipped — the model is busy with an inference "
                    + "(one Ollama call at a time; retrieval yields to generation)");
        }
        try {
            return delegate.embed(text);
        } finally {
            gate.release();
        }
    }
}
