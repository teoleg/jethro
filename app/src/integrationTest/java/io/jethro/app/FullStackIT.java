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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
                // 250ms ticks: the 50ms IT tape (25 instruments + quotes per tick) saturated
                // the 2-vCPU runner (ring-buffer drops in the logs) and starved Ollama — the
                // commentary await then missed its window. Marks still flow far faster than
                // any assertion needs.
                "jethro.trading.sim-tick-interval-millis=250",
                "jethro.ai.interval-seconds=5",
                "jethro.ai.request-timeout-seconds=180",
                // Short generations: the IT asserts tokens > 0 and real latency, not prose.
                "jethro.ai.max-output-tokens=60",
                // This IT asserts the COMMENTARY path. The hypothesis layer isn't asserted here
                // and competes for the same single-threaded Ollama on a 2-vCPU runner (its large
                // prompts every 20s queue ahead of commentary calls) — that contention is what
                // intermittently pushed the first commentary card past the 5-minute await.
                "jethro.hypothesis.enabled=false",
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
        // 8 min: a COLD 0.5b model on a busy shared runner can take minutes for its first
        // generation (load + prompt eval) even unconstrained — patience, not a weaker assert.
        JsonNode card = await("ai-commentary card", Duration.ofMinutes(8), () -> {
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

    @Test
    void referenceDataMigratesAndServesSeededBooksAndInstruments() {
        JsonNode books = await("book tree", Duration.ofSeconds(30), () -> {
            JsonNode body = getJson("/api/books");
            return body != null && body.size() == 1 ? body : null;
        });
        assertEquals("FIRM", books.get(0).get("bookId").asText());
        assertEquals(4, books.get(0).get("children").size(), "ALPHA, BETA, MACRO and the AI sleeve under FIRM");

        JsonNode instruments = getJson("/api/instruments");
        assertEquals(15, instruments.size(), "seeded multi-asset instruments incl. rates, swaps + EUR equity");
        assertEquals("AAPL", instruments.get(0).get("instrumentId").asText(), "sorted by id");
        assertEquals("1.00000000", instruments.get(0).get("contractMultiplier").asText(),
                "multiplier arrives as exact decimal string");
    }

    @Test
    void submittingAMarketOrderPersistsAndFillsAgainstTheLiveMark() {
        // Exercises the full order write path against real Postgres + Redpanda: NEW →
        // ROUTED → simulated FILL at the live mark, persisted to orders/fills and
        // published. A market order rejects when no mark is cached yet, so retry with a
        // fresh order until marks have propagated to the order module's price cache.
        String body = """
                {"bookId":"ALPHA","instrumentId":"AAPL","side":"BUY","type":"MARKET","quantity":"100"}""";

        JsonNode filled = await("market order fills", Duration.ofSeconds(120), () -> {
            JsonNode resp = postJson("/api/orders", body);
            return resp != null && "FILLED".equals(resp.path("status").asText()) ? resp : null;
        });
        assertEquals("AAPL", filled.get("instrumentId").asText());
        assertEquals("100.000000", filled.get("quantity").asText(), "quantity persisted as exact decimal");

        JsonNode fills = getJson("/api/fills");
        assertTrue(fills != null && fills.size() >= 1, "the fill persisted and is queryable");
        assertTrue(fills.get(0).get("price").asText().matches("\\d+\\.\\d{6}"),
                "fill price is an exact decimal");
    }

    @Test
    void anOrderBreachingTheBookExposureLimitIsRejectedByTheGuardrail() {
        // 5000 AAPL at ~190 ≈ 950k gross, over ALPHA's 500k cap → the deterministic
        // pre-trade guardrail rejects before any fill. Retry until the risk projection
        // has the mark it needs to value the exposure (transient "no market data"
        // rejections are skipped by the reason check).
        String body = """
                {"bookId":"ALPHA","instrumentId":"AAPL","side":"BUY","type":"MARKET","quantity":"5000"}""";

        JsonNode rejected = await("guardrail rejection", Duration.ofSeconds(120), () -> {
            JsonNode resp = postJson("/api/orders", body);
            return resp != null
                    && "REJECTED".equals(resp.path("status").asText())
                    && resp.path("reason").asText("").toLowerCase().contains("exposure") ? resp : null;
        });
        assertTrue(rejected.get("reason").asText().toLowerCase().contains("exceed"),
                "reason states the limit breach: " + rejected.get("reason").asText());
    }

    @Test
    void operationalChatAnswersAndAudits() {
        // Exercises /api/chat end to end (intent parse → deterministic answer → chat_audit
        // write via the V6 migration). Content is deterministic; don't assert the model's
        // parse (0.5b may vary) — just that a real, non-empty answer comes back.
        JsonNode resp = postJson("/api/chat", "{\"question\":\"what is the firm pnl?\"}");
        assertTrue(resp != null, "chat endpoint responded");
        assertTrue(resp.path("answer").asText("").length() > 0, "answer is non-empty");
        assertTrue(resp.path("intent").asText("").length() > 0, "intent was classified");
    }

    private JsonNode postJson(String path, String body) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            String resp = http.postForObject(
                    "http://localhost:" + port + path, new HttpEntity<>(body, headers), String.class);
            return resp == null ? null : JSON.readTree(resp);
        } catch (Exception e) {
            return null;
        }
    }

    private AiDecision pollLatestDecision() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"));
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (var consumer = new KafkaConsumer<>(props, new StringDeserializer(), new ByteArrayDeserializer())) {
            consumer.subscribe(List.of(Topics.resolved(Topics.AI_DECISIONS)));
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
