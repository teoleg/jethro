package io.jethro.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jethro.messaging.AiDecision;
import io.jethro.messaging.AvroCodec;
import io.jethro.messaging.Topics;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The no-exceptions test: real Redpanda, real Ollama, real model, no stubs anywhere.
 * Requires `docker compose up -d redpanda postgres ollama` and the model pulled
 * (CI does this; see .github/workflows/ci.yml). Asserts things only a live stack can
 * produce: marks arriving via the broker, and an AiDecision whose latency and token
 * counts came from actual generation.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "jethro.trading.sim-tick-interval-millis=50",
                "jethro.ai.interval-seconds=5",
                "jethro.ai.request-timeout-seconds=180",
        })
class FullStackIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) {
        registry.add("jethro.trading.lmdb-path", () -> tempDir.resolve("lmdb").toString());
        registry.add("jethro.ai.model",
                () -> System.getenv().getOrDefault("JETHRO_IT_MODEL", "qwen2.5:0.5b"));
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate http;

    @Test
    void marksFlowThroughTheRealBrokerToTheUi() {
        JsonNode marks = await("marks via broker", Duration.ofSeconds(90), () -> {
            JsonNode body = getJson("/api/marks");
            return body != null && body.size() >= 4 ? body : null;
        });
        assertTrue(marks.get(0).get("price").asText().matches("\\d+\\.\\d{6}"),
                "prices arrive as exact decimals");
    }

    @Test
    void realModelProducesAuditedCommentaryOnTopicAndFeed() {
        String expectedModel = System.getenv().getOrDefault("JETHRO_IT_MODEL", "qwen2.5:0.5b");

        // 1. The attention feed shows a commentary card produced by the real model
        JsonNode card = await("ai-commentary card", Duration.ofMinutes(5), () -> {
            JsonNode feed = getJson("/api/attention");
            if (feed == null) {
                return null;
            }
            for (JsonNode item : feed) {
                if ("ai-commentary".equals(item.get("kind").asText())) {
                    return item;
                }
            }
            return null;
        });
        assertTrue(card.get("title").asText().contains(expectedModel));
        assertTrue(card.get("body").asText().strip().length() > 10, "real commentary has content");

        // 2. The AiDecision is on the real ai.decisions topic with generation evidence
        AiDecision decision = await("AiDecision on topic", Duration.ofSeconds(60),
                () -> pollLatestDecision());
        assertEquals(expectedModel, decision.getModelId());
        assertTrue(decision.getLatencyMillis() > 50,
                "real generation takes real time (stub answers in ~1ms), got " + decision.getLatencyMillis());
        assertTrue(decision.getOutputTokens() > 0, "token counts must come from the model");
        assertTrue(decision.getContextSnapshotJson().contains("\"marks\""));
        assertEquals(64, decision.getContextHash().length());
    }

    private AiDecision pollLatestDecision() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"));
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (var consumer = new KafkaConsumer<>(props, new StringDeserializer(), new ByteArrayDeserializer())) {
            consumer.subscribe(List.of(Topics.AI_DECISIONS));
            var records = consumer.poll(Duration.ofSeconds(5));
            AiDecision latest = null;
            for (var record : records) {
                latest = AvroCodec.decode(record.value(), AiDecision.class);
            }
            return latest;
        }
    }

    private JsonNode getJson(String path) {
        try {
            String body = http.getForObject("http://localhost:" + port + path, String.class);
            return body == null ? null : JSON.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    private static <T> T await(String what, Duration timeout, Supplier<T> probe) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            T result = probe.get();
            if (result != null) {
                return result;
            }
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted awaiting " + what);
            }
        }
        throw new AssertionError("timed out awaiting " + what + " after " + timeout);
    }
}
