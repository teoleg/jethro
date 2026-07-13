package io.jethro.app.ai;

import io.jethro.trading.algo.inference.InferenceException;
import io.jethro.trading.algo.inference.InferenceRequest;
import io.jethro.trading.algo.inference.InferenceResult;
import io.jethro.trading.algo.inference.ModelInferenceClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The decorator records every call (success + failure) and never changes the caller's outcome. */
class MonitoringInferenceClientTest {

    private static final InferenceRequest REQ = new InferenceRequest("sys", "user", 200);

    @Test
    void recordsSuccessWithTheModelsCountsAndReturnsResult() {
        var monitor = new InferenceMonitor();
        ModelInferenceClient delegate = new ModelInferenceClient() {
            public String modelId() {
                return "qwen2.5:3b";
            }

            public InferenceResult complete(InferenceRequest r) {
                return new InferenceResult("[]", "qwen2.5:3b", 1_500, 40, 90);
            }
        };
        var client = new MonitoringInferenceClient(delegate, monitor);

        InferenceResult result = client.complete(REQ);
        assertEquals("[]", result.text());
        var run = monitor.recent(1).get(0);
        assertTrue(run.ok());
        assertEquals(1_500, run.latencyMillis());
        assertEquals(40, run.inputTokens());
        assertEquals(90, run.outputTokens());
    }

    @Test
    void recordsFailureAndRethrows() {
        var monitor = new InferenceMonitor();
        ModelInferenceClient delegate = new ModelInferenceClient() {
            public String modelId() {
                return "qwen2.5:3b";
            }

            public InferenceResult complete(InferenceRequest r) {
                throw new InferenceException("ollama unreachable");
            }
        };
        var client = new MonitoringInferenceClient(delegate, monitor);

        assertThrows(InferenceException.class, () -> client.complete(REQ));
        var run = monitor.recent(1).get(0);
        assertFalse(run.ok(), "failure recorded");
        assertEquals("ollama unreachable", run.error());
        assertEquals(1, monitor.summary().failures());
    }
}
