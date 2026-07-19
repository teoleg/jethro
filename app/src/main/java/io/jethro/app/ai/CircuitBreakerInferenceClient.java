package io.jethro.app.ai;

import io.jethro.trading.algo.inference.InferenceException;
import io.jethro.trading.algo.inference.InferenceRequest;
import io.jethro.trading.algo.inference.InferenceResult;
import io.jethro.trading.algo.inference.ModelInferenceClient;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * A circuit breaker over the local model (ADR-0016). When Ollama wedges, a naive loop keeps firing
 * calls that each hang to the full request timeout (~120s) and hammer the sick model every cycle —
 * the "constantly failing" death-spiral. After {@code failureThreshold} consecutive failures the
 * breaker OPENS: for {@code cooldown} it fails in microseconds without touching Ollama, giving it
 * room to recover. The first call after the cooldown is a probe — success closes the breaker, a
 * failure re-opens it. Advisory model only; risk guardrails are deterministic and never wait on this.
 */
public final class CircuitBreakerInferenceClient implements ModelInferenceClient {

    private final ModelInferenceClient delegate;
    private final int failureThreshold;
    private final long cooldownMillis;
    private final LongSupplier clock;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long openUntilMillis = 0;
    private volatile String lastError = "";

    public CircuitBreakerInferenceClient(ModelInferenceClient delegate, int failureThreshold, Duration cooldown) {
        this(delegate, failureThreshold, cooldown, System::currentTimeMillis);
    }

    CircuitBreakerInferenceClient(ModelInferenceClient delegate, int failureThreshold, Duration cooldown,
                                  LongSupplier clock) {
        this.delegate = delegate;
        this.failureThreshold = Math.max(1, failureThreshold);
        this.cooldownMillis = Math.max(0, cooldown.toMillis());
        this.clock = clock;
    }

    @Override
    public String modelId() {
        return delegate.modelId();
    }

    @Override
    public InferenceResult complete(InferenceRequest request) {
        long now = clock.getAsLong();
        long openUntil = openUntilMillis;
        if (now < openUntil) {
            throw new InferenceException("circuit open — Ollama unhealthy, backing off "
                    + ((openUntil - now) / 1000 + 1) + "s (last error: " + lastError + ")");
        }
        try {
            InferenceResult result = delegate.complete(request);
            consecutiveFailures.set(0); // a good call (or the probe) closes the breaker
            return result;
        } catch (RuntimeException e) {
            lastError = e.getMessage();
            if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
                openUntilMillis = clock.getAsLong() + cooldownMillis;
            }
            throw e;
        }
    }
}
