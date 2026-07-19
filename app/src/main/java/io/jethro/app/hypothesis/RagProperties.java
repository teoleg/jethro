package io.jethro.app.hypothesis;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Retrieval-augmented context config (ADR-0035). ON by default — retrieval is the point: it
 * de-dups triggers semantically (beyond the deterministic id/text guard, so the same story doesn't
 * re-fire and burn model calls) and grounds theses in past outcomes. It needs a local embedding
 * model pulled ({@code ollama pull nomic-embed-text}); if none is present it degrades silently to
 * the deterministic guard (best-effort — see {@code HypothesisMemory}), so CI/offline still run.
 * Set {@code jethro.rag.enabled=false} to force it off.
 */
@ConfigurationProperties(prefix = "jethro.rag")
public record RagProperties(Boolean enabled, String model, Double dedupThreshold,
                            Double recallThreshold, Integer memoryCapacity, Integer timeoutSeconds,
                            String ollamaBaseUrl) {

    public boolean enabledOrDefault() {
        return enabled == null || enabled; // ON unless explicitly disabled
    }

    public String modelOrDefault() {
        return model != null && !model.isBlank() ? model : "nomic-embed-text";
    }

    /** Cosine ≥ this ⇒ a semantic duplicate. High (0.92) so only genuinely-same calls collapse. */
    public double dedupThresholdOrDefault() {
        return dedupThreshold != null ? dedupThreshold : 0.92;
    }

    /** Cosine ≥ this ⇒ a past outcome is "similar enough" to recall into the prompt. Lower than
     *  dedup (0.55): retrieval wants related setups, not identical ones. */
    public double recallThresholdOrDefault() {
        return recallThreshold != null ? recallThreshold : 0.55;
    }

    public int memoryCapacityOrDefault() {
        return memoryCapacity != null && memoryCapacity > 0 ? memoryCapacity : 500;
    }

    public int timeoutSecondsOrDefault() {
        return timeoutSeconds != null && timeoutSeconds > 0 ? timeoutSeconds : 20;
    }

    public String ollamaBaseUrlOrDefault() {
        return ollamaBaseUrl != null && !ollamaBaseUrl.isBlank() ? ollamaBaseUrl : "http://localhost:11434";
    }
}
