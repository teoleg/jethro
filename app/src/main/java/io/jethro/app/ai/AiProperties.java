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
        long requestTimeoutSeconds,
        // Ops/resource dials (not money/risk). keepAlive: how long Ollama keeps the model resident after
        // a call — SHORT so idle gaps unload it and reclaim llama-server's memory (the hardcoded 30m it
        // replaced pinned the model forever under our polling). numCtx: caps the per-request KV cache.
        // recycleMinutes: force-evicts the chat model on this cadence to reset llama-server's slow native
        // growth (observed leak: RSS climbs ~1.6GB over model weights under sustained calls) — 0 disables.
        String keepAlive,
        int numCtx,
        long recycleMinutes) {

    public AiProperties {
        keepAlive = (keepAlive == null || keepAlive.isBlank()) ? "5m" : keepAlive;
        numCtx = numCtx > 0 ? numCtx : 2048;
        recycleMinutes = recycleMinutes > 0 ? recycleMinutes : 30;
    }
}
