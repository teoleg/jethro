package io.jethro.trading.algo.inference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Ollama adapter (ADR-0016): local SLM over plain HTTP ({@code POST /api/generate},
 * non-streaming). Free, offline, no API keys — the local-dev-first tier of the
 * model-inference SPI.
 */
public final class OllamaClient implements ModelInferenceClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient httpClient;
    private final URI generateUri;
    private final String model;
    private final Duration requestTimeout;
    private final String keepAlive;
    private final int numCtx;

    /** Defaults keepAlive/numCtx (tests + warm-restart callers): 5m resident, 2048-token context. */
    public OllamaClient(String baseUrl, String model, Duration requestTimeout) {
        this(baseUrl, model, requestTimeout, "5m", 2048);
    }

    public OllamaClient(String baseUrl, String model, Duration requestTimeout, String keepAlive, int numCtx) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.generateUri = URI.create(baseUrl.replaceAll("/$", "") + "/api/generate");
        this.model = model;
        this.requestTimeout = requestTimeout;
        this.keepAlive = keepAlive;
        this.numCtx = numCtx;
    }

    @Override
    public String modelId() {
        return model;
    }

    @Override
    public InferenceResult complete(InferenceRequest request) {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", model);
        body.put("prompt", request.userPrompt());
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            body.put("system", request.systemPrompt());
        }
        body.put("stream", false);
        // keep_alive is deliberately SHORT and configurable (jethro.ai.keep-alive): it must be long enough
        // to stay warm across an active narration burst, but short enough that a genuinely idle box unloads
        // the model and RECLAIMS llama-server's memory. (A hardcoded 30m here previously pinned the model
        // resident forever under our ~20s polling, so its slow native growth never reset — the box-freeze
        // leak.) num_ctx caps the per-request KV cache instead of using the model's larger default.
        body.put("keep_alive", keepAlive);
        ObjectNode options = body.putObject("options");
        options.put("num_predict", request.maxOutputTokens());
        options.put("num_ctx", numCtx);

        HttpRequest httpRequest;
        try {
            httpRequest = HttpRequest.newBuilder(generateUri)
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build();
        } catch (IOException e) {
            throw new InferenceException("failed to serialize ollama request", e);
        }

        long startedAt = System.currentTimeMillis();
        HttpResponse<String> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new InferenceException("ollama unreachable at " + generateUri, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InferenceException("interrupted waiting for ollama", e);
        }
        long latency = System.currentTimeMillis() - startedAt;

        if (response.statusCode() != 200) {
            throw new InferenceException("ollama returned HTTP " + response.statusCode()
                    + ": " + truncate(response.body()));
        }
        try {
            JsonNode json = JSON.readTree(response.body());
            return new InferenceResult(
                    json.path("response").asText(""),
                    model,
                    latency,
                    json.path("prompt_eval_count").asLong(0),
                    json.path("eval_count").asLong(0));
        } catch (IOException e) {
            throw new InferenceException("unparseable ollama response: " + truncate(response.body()), e);
        }
    }

    private static String truncate(String body) {
        return body == null ? "" : body.substring(0, Math.min(body.length(), 300));
    }
}
