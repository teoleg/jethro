package io.jethro.app;

import com.sun.net.httpserver.HttpServer;
import io.jethro.app.ai.BufferingDecisionSink;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0016 definition of done: the agent loop runs in the assembled app — computed
 * market state goes to the model, commentary comes back, and an AiDecision audit
 * event is recorded. Ollama is stubbed; the real model is a compose container.
 */
@SpringBootTest(properties = {"jethro.ai.interval-seconds=1", "jethro.kafka.enabled=false", "jethro.refdata.enabled=false"})
class AiCommentarySmokeTest {

    @TempDir
    static Path tempDir;

    static HttpServer stubOllama;

    @BeforeAll
    static void startStub() throws IOException {
        stubOllama = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stubOllama.createContext("/api/generate", exchange -> {
            byte[] response = """
                    {"model":"stub-3b","response":"Feed healthy; all four instruments ticking.",
                     "prompt_eval_count":80,"eval_count":15,"done":true}"""
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        stubOllama.start();
    }

    @AfterAll
    static void stopStub() {
        stubOllama.stop(0);
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) {
        registry.add("jethro.trading.lmdb-path", () -> tempDir.resolve("lmdb").toString());
        registry.add("jethro.ai.base-url",
                () -> "http://127.0.0.1:" + stubOllama.getAddress().getPort());
    }

    @Autowired
    BufferingDecisionSink sink;

    @Test
    void agentLoopProducesAuditedCommentary() throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (sink.recent().isEmpty()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("no AiDecision recorded within 10s");
            }
            Thread.sleep(50);
        }

        var decision = sink.recent().get(0);
        assertEquals("qwen2.5:3b", decision.getModelId()); // configured model id travels through
        assertTrue(decision.getContextSnapshotJson().contains("\"marks\""),
                "decision must embed the computed market context");
        assertFalse(decision.getContextHash().isBlank());
        assertTrue(decision.getProposedActionsJson().contains("ticking"),
                "commentary text must be recorded");
        assertTrue(decision.getInputTokens() > 0 && decision.getOutputTokens() > 0,
                "token counts feed finops (ADR-0011)");
    }
}
