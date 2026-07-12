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

    public OllamaClient(String baseUrl, String model, Duration requestTimeout) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.generateUri = URI.create(baseUrl.replaceAll("/$", "") + "/api/generate");
        this.model = model;
        this.requestTimeout = requestTimeout;
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
        body.putObject("options").put("num_predict", request.maxOutputTokens());

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
