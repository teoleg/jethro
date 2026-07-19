package io.jethro.app.ai;

import io.jethro.trading.algo.inference.InferenceException;
import io.jethro.trading.algo.inference.InferenceRequest;
import io.jethro.trading.algo.inference.InferenceResult;
import io.jethro.trading.algo.inference.ModelInferenceClient;

/**
 * Serializes access to the local SLM (ADR-0016): only ONE model call runs at a time. A small box
 * runs a single model instance, so overlapping calls (the hypothesis loop, the commentary loop, and
 * — since RAG — embedding calls) queue at Ollama and the waiting one times out as "ollama
 * unreachable". This guard makes a call that arrives while another is in flight fail fast and
 * cleanly instead of piling up — the caller already tolerates a skipped cycle and retries. It shares
 * the {@link OllamaGate} with the embedding client so generate and embed can't collide either, and
 * wraps the client OUTSIDE the monitor, so a fast busy-skip isn't recorded as a model failure.
 */
public final class SingleFlightInferenceClient implements ModelInferenceClient {

    private final ModelInferenceClient delegate;
    private final OllamaGate gate;

    public SingleFlightInferenceClient(ModelInferenceClient delegate, OllamaGate gate) {
        this.delegate = delegate;
        this.gate = gate;
    }

    @Override
    public String modelId() {
        return delegate.modelId();
    }

    @Override
    public InferenceResult complete(InferenceRequest request) {
        if (!gate.tryAcquire()) {
            throw new InferenceException("skipped — another model call is already running "
                    + "(the box runs one at a time; this cycle yields to avoid a pile-up)");
        }
        try {
            return delegate.complete(request);
        } finally {
            gate.release();
        }
    }
}
