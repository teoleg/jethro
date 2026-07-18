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
 * Ollama embedding adapter (ADR-0035): local embeddings over plain HTTP
 * ({@code POST /api/embeddings}), free and offline like the generation tier (ADR-0016). Mirrors
 * {@link OllamaClient}'s transport. A missing model or an unreachable server throws
 * {@link InferenceException}; the caller treats retrieval as best-effort.
 */
public final class OllamaEmbeddingClient implements EmbeddingClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient httpClient;
    private final URI embeddingsUri;
    private final String model;
    private final Duration requestTimeout;

    public OllamaEmbeddingClient(String baseUrl, String model, Duration requestTimeout) {
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.embeddingsUri = URI.create(baseUrl.replaceAll("/$", "") + "/api/embeddings");
        this.model = model;
        this.requestTimeout = requestTimeout;
    }

    @Override
    public String modelId() {
        return model;
    }

    @Override
    public float[] embed(String text) {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", model);
        body.put("prompt", text == null ? "" : text);
        body.put("keep_alive", "30m");

        HttpRequest httpRequest;
        try {
            httpRequest = HttpRequest.newBuilder(embeddingsUri)
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build();
        } catch (IOException e) {
            throw new InferenceException("failed to serialize ollama embedding request", e);
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new InferenceException("ollama unreachable at " + embeddingsUri, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InferenceException("interrupted waiting for ollama embedding", e);
        }
        if (response.statusCode() != 200) {
            // Surface Ollama's own reason (e.g. "model 'nomic-embed-text' not found, try pulling it
            // first") so the ops view says WHY, not just that it failed.
            String errorBody = response.body();
            String reason = errorBody == null || errorBody.isBlank() ? ""
                    : " — " + (errorBody.length() > 200 ? errorBody.substring(0, 200) : errorBody)
                            .replaceAll("\\s+", " ").trim();
            throw new InferenceException("ollama embeddings HTTP " + response.statusCode() + reason);
        }
        return parseEmbedding(response.body());
    }

    /** Parses Ollama's {@code {"embedding": [..]}} body into a float vector. Package-private for tests. */
    static float[] parseEmbedding(String responseBody) {
        try {
            JsonNode arr = JSON.readTree(responseBody).path("embedding");
            if (!arr.isArray() || arr.isEmpty()) {
                throw new InferenceException("ollama embedding response had no vector");
            }
            float[] out = new float[arr.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = (float) arr.get(i).asDouble();
            }
            return out;
        } catch (IOException e) {
            throw new InferenceException("unparseable ollama embedding response", e);
        }
    }
}
