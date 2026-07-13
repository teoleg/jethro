package io.jethro.app.ai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Operational monitor for local-SLM (Ollama) load — every inference call, success or failure,
 * with its latency and token counts. Feeds the ops page so an operator can see how hard the box
 * is being pushed and how fast the model is running, independent of what the calls were for
 * (commentary, hypotheses, chat). A bounded in-memory ring (no DB) — this is live telemetry, not
 * an audit record (the audited copy is the ai.decisions event). Thread-safe; never on the tick
 * path (invariant 7).
 */
public final class InferenceMonitor {

    private static final int CAPACITY = 300;

    /** One inference call. latencyMillis is the model's own round-trip; tok/s = gen tokens ÷ latency. */
    public record Run(long timestampMillis, String model, boolean ok, long latencyMillis,
                      long inputTokens, long outputTokens, String error) {

        public double tokensPerSecond() {
            return ok && latencyMillis > 0 ? outputTokens * 1000.0 / latencyMillis : 0;
        }
    }

    /** Rolling summary over the retained window. */
    public record Summary(int total, int failures, long avgLatencyMillis, long p95LatencyMillis,
                          double avgTokensPerSecond, long lastRunMillis, String model) {
    }

    private final Deque<Run> runs = new ArrayDeque<>();

    public synchronized void record(Run run) {
        if (runs.size() == CAPACITY) {
            runs.removeFirst();
        }
        runs.addLast(run);
    }

    /** Recent runs, newest first, up to {@code limit}. */
    public synchronized List<Run> recent(int limit) {
        List<Run> out = new ArrayList<>(runs);
        java.util.Collections.reverse(out); // newest first
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    public synchronized Summary summary() {
        if (runs.isEmpty()) {
            return new Summary(0, 0, 0, 0, 0, 0, null);
        }
        int failures = 0;
        long latencySum = 0;
        double tpsSum = 0;
        int okCount = 0;
        long lastRun = 0;
        String model = null;
        List<Long> okLatencies = new ArrayList<>();
        for (Run r : runs) {
            lastRun = Math.max(lastRun, r.timestampMillis());
            model = r.model();
            if (r.ok()) {
                okCount++;
                latencySum += r.latencyMillis();
                tpsSum += r.tokensPerSecond();
                okLatencies.add(r.latencyMillis());
            } else {
                failures++;
            }
        }
        long avgLatency = okCount > 0 ? latencySum / okCount : 0;
        double avgTps = okCount > 0 ? tpsSum / okCount : 0;
        return new Summary(runs.size(), failures, avgLatency, percentile(okLatencies, 95),
                avgTps, lastRun, model);
    }

    private static long percentile(List<Long> values, int pct) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        java.util.Collections.sort(sorted);
        int idx = (int) Math.ceil(pct / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }
}
