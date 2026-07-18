package io.jethro.app.ai;

import io.jethro.trading.algo.inference.InferenceException;
import io.jethro.trading.algo.inference.InferenceRequest;
import io.jethro.trading.algo.inference.InferenceResult;
import io.jethro.trading.algo.inference.ModelInferenceClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The breaker trips after a run of failures, fails fast without touching Ollama while open, and
 *  probes/closes once the cooldown elapses — so a wedged model can't be hammered every cycle. */
class CircuitBreakerInferenceClientTest {

    private static final InferenceRequest REQ = new InferenceRequest("sys", "user", 10);

    private static final class Fake implements ModelInferenceClient {
        boolean fail = true;
        int calls = 0;
        @Override public String modelId() { return "m"; }
        @Override public InferenceResult complete(InferenceRequest r) {
            calls++;
            if (fail) {
                throw new InferenceException("ollama unreachable");
            }
            return new InferenceResult("ok", "m", 1, 1, 1);
        }
    }

    @Test
    void opensAfterThresholdAndThenFailsFastWithoutCallingOllama() {
        var fake = new Fake();
        long[] now = {0};
        var cb = new CircuitBreakerInferenceClient(fake, 3, Duration.ofSeconds(60), () -> now[0]);
        for (int i = 0; i < 3; i++) {
            assertThrows(InferenceException.class, () -> cb.complete(REQ));
        }
        assertEquals(3, fake.calls);
        var ex = assertThrows(InferenceException.class, () -> cb.complete(REQ));
        assertTrue(ex.getMessage().contains("circuit open"), ex.getMessage());
        assertEquals(3, fake.calls, "an open breaker must not touch the model");
    }

    @Test
    void probesAndClosesOnceTheCooldownElapses() {
        var fake = new Fake();
        long[] now = {0};
        var cb = new CircuitBreakerInferenceClient(fake, 2, Duration.ofSeconds(30), () -> now[0]);
        assertThrows(InferenceException.class, () -> cb.complete(REQ));
        assertThrows(InferenceException.class, () -> cb.complete(REQ)); // trips open
        assertThrows(InferenceException.class, () -> cb.complete(REQ)); // fast-fail, no call
        assertEquals(2, fake.calls);

        now[0] = 31_000;   // cooldown elapsed
        fake.fail = false; // model recovered
        assertEquals("ok", cb.complete(REQ).text(), "the probe call runs");
        assertEquals(3, fake.calls);
        cb.complete(REQ); // breaker closed — normal calls pass
        assertEquals(4, fake.calls);
    }
}
