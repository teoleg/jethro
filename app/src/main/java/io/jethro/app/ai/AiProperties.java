package io.jethro.app.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Wiring config for the local SLM tier (ADR-0016). */
@ConfigurationProperties(prefix = "jethro.ai")
public record AiProperties(
        boolean enabled,
        String baseUrl,
        String model,
        long intervalSeconds,
        int maxOutputTokens,
        long requestTimeoutSeconds) {
}
