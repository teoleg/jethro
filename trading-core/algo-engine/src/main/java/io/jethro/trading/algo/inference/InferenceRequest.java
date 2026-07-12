package io.jethro.trading.algo.inference;

/** A single model inference call (ADR-0010/0016 model-inference SPI). */
public record InferenceRequest(String systemPrompt, String userPrompt, int maxOutputTokens) {

    public InferenceRequest {
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("userPrompt must be non-blank");
        }
        if (maxOutputTokens <= 0) {
            throw new IllegalArgumentException("maxOutputTokens must be positive");
        }
    }
}
