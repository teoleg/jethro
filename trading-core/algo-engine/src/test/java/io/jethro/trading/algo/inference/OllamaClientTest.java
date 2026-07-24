package io.jethro.trading.algo.inference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** OllamaClient against a stub HTTP server speaking the /api/generate protocol. */
class OllamaClientTest {

    private HttpServer server;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private volatile int statusToReturn = 200;
    private volatile String bodyToReturn = """
            {"model":"test-model","response":"AAPL is drifting up; feed healthy.",
             "prompt_eval_count":57,"eval_count":21,"done":true}""";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/generate", exchange -> {
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = bodyToReturn.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusToReturn, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private OllamaClient client() {
        return new OllamaClient("http://127.0.0.1:" + server.getAddress().getPort(),
                "test-model", Duration.ofSeconds(5));
    }

    @Test
    void parsesResponseAndTokenCounts() {
        var result = client().complete(new InferenceRequest("sys", "what do you see?", 64));
        assertEquals("AAPL is drifting up; feed healthy.", result.text());
        assertEquals("test-model", result.modelId());
        assertEquals(57, result.inputTokens());
        assertEquals(21, result.outputTokens());
        assertTrue(result.latencyMillis() >= 0);
    }

    @Test
    void sendsModelPromptAndNoStream() {
        client().complete(new InferenceRequest("the system prompt", "the user prompt", 64));
        String body = lastRequestBody.get();
        assertTrue(body.contains("\"model\":\"test-model\""));
        assertTrue(body.contains("\"prompt\":\"the user prompt\""));
        assertTrue(body.contains("\"system\":\"the system prompt\""));
        assertTrue(body.contains("\"stream\":false"));
        assertTrue(body.contains("\"num_predict\":64"));
        // Footprint dials: default keep_alive is SHORT (5m, not the old hardcoded 30m) and num_ctx is capped.
        assertTrue(body.contains("\"keep_alive\":\"5m\""), body);
        assertTrue(body.contains("\"num_ctx\":2048"), body);
    }

    @Test
    void keepAliveAndNumCtxAreConfigurable() {
        var configured = new OllamaClient("http://127.0.0.1:" + server.getAddress().getPort(),
                "test-model", Duration.ofSeconds(5), "90s", 1024);
        configured.complete(new InferenceRequest(null, "hi", 16));
        String body = lastRequestBody.get();
        assertTrue(body.contains("\"keep_alive\":\"90s\""), body);
        assertTrue(body.contains("\"num_ctx\":1024"), body);
    }

    @Test
    void non200BecomesInferenceException() {
        statusToReturn = 500;
        bodyToReturn = "{\"error\":\"model not found\"}";
        assertThrows(InferenceException.class,
                () -> client().complete(new InferenceRequest(null, "hi", 16)));
    }

    @Test
    void unreachableServerBecomesInferenceException() {
        var dead = new OllamaClient("http://127.0.0.1:1", "test-model", Duration.ofSeconds(1));
        assertThrows(InferenceException.class,
                () -> dead.complete(new InferenceRequest(null, "hi", 16)));
    }
}
