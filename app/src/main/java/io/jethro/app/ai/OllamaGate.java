package io.jethro.app.ai;

import java.util.concurrent.Semaphore;

/**
 * One-at-a-time gate over the local Ollama instance (ADR-0016). The box runs a single model, so
 * a generate call and an embedding call that overlap force Ollama to swap models (or simply queue)
 * and the loser times out as "ollama unreachable". This gate is shared by the inference
 * single-flight ({@link SingleFlightInferenceClient}) AND the embedding client
 * ({@link SingleFlightEmbeddingClient}) so <em>any</em> two model calls serialize, never collide.
 *
 * <p>{@link #tryAcquire()} is non-blocking: a caller that loses skips its cycle (best-effort —
 * commentary/hypotheses tolerate a skipped tick and retry) rather than piling up behind a busy model.
 */
public final class OllamaGate {

    private final Semaphore permit = new Semaphore(1);

    public boolean tryAcquire() {
        return permit.tryAcquire();
    }

    public void release() {
        permit.release();
    }
}
