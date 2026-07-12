package io.jethro.trading.algo.inference;

/**
 * Result of a model call, carrying everything the ai.decisions audit event needs
 * (ADR-0010): model identity, latency, token counts for finops (ADR-0011).
 */
public record InferenceResult(
        String text,
        String modelId,
        long latencyMillis,
        long inputTokens,
        long outputTokens) {
}
