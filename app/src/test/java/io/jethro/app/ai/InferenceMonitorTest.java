package io.jethro.app.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ops telemetry math: success/failure counts, latency averages, tok/s, and newest-first order. */
class InferenceMonitorTest {

    private static InferenceMonitor.Run ok(long ts, long latency, long in, long out) {
        return new InferenceMonitor.Run(ts, "qwen2.5:3b", true, latency, in, out, null);
    }

    @Test
    void summarizesLatencyAndThroughputOverSuccessesOnly() {
        var m = new InferenceMonitor();
        m.record(ok(1_000, 1_000, 50, 100)); // 100 tok/s
        m.record(ok(2_000, 3_000, 60, 150)); // 50 tok/s
        m.record(new InferenceMonitor.Run(3_000, "qwen2.5:3b", false, 30_000, 0, 0, "timeout"));

        var s = m.summary();
        assertEquals(3, s.total());
        assertEquals(1, s.failures());
        assertEquals(2_000, s.avgLatencyMillis(), "avg over the two successes (1000+3000)/2");
        assertEquals(75.0, s.avgTokensPerSecond(), 0.01, "(100+50)/2 tok/s — failures excluded");
        assertEquals(3_000, s.lastRunMillis());
    }

    @Test
    void tokensPerSecondIsGenTokensOverLatency() {
        assertEquals(50.0, ok(0, 2_000, 10, 100).tokensPerSecond(), 0.001);
        assertEquals(0.0, new InferenceMonitor.Run(0, "m", false, 500, 0, 0, "x").tokensPerSecond(),
                "failures report 0 tok/s");
    }

    @Test
    void recentIsNewestFirstAndRespectsLimit() {
        var m = new InferenceMonitor();
        for (int i = 0; i < 5; i++) {
            m.record(ok(i, 100, 1, 1));
        }
        List<InferenceMonitor.Run> recent = m.recent(3);
        assertEquals(3, recent.size());
        assertEquals(4, recent.get(0).timestampMillis(), "newest first");
        assertEquals(2, recent.get(2).timestampMillis());
    }

    @Test
    void emptyMonitorHasZeroSummary() {
        var s = new InferenceMonitor().summary();
        assertEquals(0, s.total());
        assertEquals(0, s.avgLatencyMillis());
        assertTrue(s.model() == null);
    }
}
