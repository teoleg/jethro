package io.jethro.app.ai;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Operational view of local-SLM (Ollama) load (ADR-0016): recent inference calls with their
 * latency and token throughput, plus a rolling summary. For running the box, not trading — shows
 * how hard the model is being pushed and whether it's keeping up with the hypothesis cadence.
 */
@RestController
public final class InferenceMonitorController {

    public record RunDto(long timestampMillis, String model, boolean ok, long latencyMillis,
                         long inputTokens, long outputTokens, double tokensPerSecond, String error) {
    }

    public record SummaryDto(int total, int successes, int failures, long avgLatencyMillis,
                             long p95LatencyMillis, double avgTokensPerSecond, long lastRunMillis, String model) {
    }

    public record LlmRunsDto(SummaryDto summary, List<RunDto> runs) {
    }

    private final InferenceMonitor monitor;

    public InferenceMonitorController(InferenceMonitor monitor) {
        this.monitor = monitor;
    }

    @GetMapping("/api/llm/runs")
    public LlmRunsDto runs(@RequestParam(defaultValue = "100") int limit) {
        InferenceMonitor.Summary s = monitor.summary();
        List<RunDto> runs = monitor.recent(limit).stream().map(r -> new RunDto(
                r.timestampMillis(), r.model(), r.ok(), r.latencyMillis(),
                r.inputTokens(), r.outputTokens(), round1(r.tokensPerSecond()), r.error())).toList();
        SummaryDto summary = new SummaryDto(s.total(), s.total() - s.failures(), s.failures(),
                s.avgLatencyMillis(), s.p95LatencyMillis(), round1(s.avgTokensPerSecond()),
                s.lastRunMillis(), s.model());
        return new LlmRunsDto(summary, runs);
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
