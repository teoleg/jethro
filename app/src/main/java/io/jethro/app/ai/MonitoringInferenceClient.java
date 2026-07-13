package io.jethro.app.ai;

import io.jethro.trading.algo.inference.InferenceRequest;
import io.jethro.trading.algo.inference.InferenceResult;
import io.jethro.trading.algo.inference.ModelInferenceClient;

/**
 * Decorator that records every inference call into the {@link InferenceMonitor} for the ops page,
 * then delegates. Wraps the real client at the bean level, so ALL callers (commentary, hypotheses,
 * chat) are captured in one place with no changes to them. Failures are recorded (with wall-clock
 * latency) and rethrown unchanged — callers still degrade exactly as before.
 */
public final class MonitoringInferenceClient implements ModelInferenceClient {

    private final ModelInferenceClient delegate;
    private final InferenceMonitor monitor;

    public MonitoringInferenceClient(ModelInferenceClient delegate, InferenceMonitor monitor) {
        this.delegate = delegate;
        this.monitor = monitor;
    }

    @Override
    public String modelId() {
        return delegate.modelId();
    }

    @Override
    public InferenceResult complete(InferenceRequest request) {
        long startNanos = System.nanoTime();
        try {
            InferenceResult result = delegate.complete(request);
            monitor.record(new InferenceMonitor.Run(System.currentTimeMillis(), result.modelId(),
                    true, result.latencyMillis(), result.inputTokens(), result.outputTokens(), null));
            return result;
        } catch (RuntimeException e) {
            long latency = (System.nanoTime() - startNanos) / 1_000_000;
            monitor.record(new InferenceMonitor.Run(System.currentTimeMillis(), delegate.modelId(),
                    false, latency, 0, 0, e.getMessage()));
            throw e;
        }
    }
}
