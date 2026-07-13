package io.jethro.app.ai;

import io.jethro.trading.algo.inference.InferenceException;
import io.jethro.trading.algo.inference.InferenceRequest;
import io.jethro.trading.algo.inference.InferenceResult;
import io.jethro.trading.algo.inference.ModelInferenceClient;

import java.util.concurrent.Semaphore;

/**
 * Serializes access to the local SLM (ADR-0016): only ONE inference runs at a time. A small box
 * runs a single model instance, so overlapping calls (the hypothesis loop and the commentary loop
 * both fire independently, each ~100s on a Pi) queue at Ollama and the waiting one times out as
 * "ollama unreachable". This guard makes a call that arrives while another is in flight fail fast
 * and cleanly instead of piling up — the caller already tolerates a skipped cycle and retries. It
 * wraps the client OUTSIDE the monitor, so a fast busy-skip isn't recorded as a model failure.
 */
public final class SingleFlightInferenceClient implements ModelInferenceClient {

    private final ModelInferenceClient delegate;
    private final Semaphore permit = new Semaphore(1);

    public SingleFlightInferenceClient(ModelInferenceClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public String modelId() {
        return delegate.modelId();
    }

    @Override
    public InferenceResult complete(InferenceRequest request) {
        if (!permit.tryAcquire()) {
            throw new InferenceException("skipped — another inference is already running "
                    + "(the model runs one at a time; this cycle yields to avoid a pile-up)");
        }
        try {
            return delegate.complete(request);
        } finally {
            permit.release();
        }
    }
}
