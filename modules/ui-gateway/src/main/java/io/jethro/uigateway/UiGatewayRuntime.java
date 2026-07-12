package io.jethro.uigateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jethro.messaging.AiDecision;
import io.jethro.messaging.AvroCodec;
import io.jethro.messaging.MarkEvent;
import io.jethro.messaging.Topics;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ui-gateway's consumer side (ADR-0015: even in-process, cross-domain flow arrives
 * via topics): md.marks → MarkState, ai.decisions → attention commentary cards.
 * Deterministic rules re-evaluate on a cadence; SSE pushes deltas to browsers.
 */
public final class UiGatewayRuntime implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(UiGatewayRuntime.class);
    private static final Duration POLL = Duration.ofMillis(500);
    private static final long RULES_EVERY_MILLIS = 2_000;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String bootstrapServers;
    private final MarkState markState;
    private final MarkHistory markHistory;
    private final AttentionFeed feed;
    private final AttentionRules rules;
    private final SseBroadcaster sse;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicLong marksConsumed = new java.util.concurrent.atomic.AtomicLong();
    private volatile Thread consumerThread;

    public UiGatewayRuntime(String bootstrapServers, MarkState markState, MarkHistory markHistory,
                            AttentionFeed feed, AttentionRules rules, SseBroadcaster sse) {
        this.bootstrapServers = bootstrapServers;
        this.markState = markState;
        this.markHistory = markHistory;
        this.feed = feed;
        this.rules = rules;
        this.sse = sse;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(this::consumeLoop, "ui-gateway-consumer");
        thread.setDaemon(true);
        consumerThread = thread;
        thread.start();
    }

    private void consumeLoop() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "ui-gateway");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        // UI state is last-value + bounded feed: duplicate delivery is naturally
        // idempotent here (upsert by key) — invariant 8 satisfied by shape.
        long lastRulesRun = 0;
        try (var consumer = new KafkaConsumer<>(props, new StringDeserializer(), new ByteArrayDeserializer())) {
            consumer.subscribe(List.of(Topics.MD_MARKS, Topics.AI_DECISIONS));
            log.info("ui-gateway consuming {} and {} from {}", Topics.MD_MARKS, Topics.AI_DECISIONS, bootstrapServers);
            while (running.get()) {
                var records = consumer.poll(POLL);
                boolean marksChanged = false;
                for (var record : records) {
                    try {
                        if (Topics.MD_MARKS.equals(record.topic())) {
                            onMark(AvroCodec.decode(record.value(), MarkEvent.class));
                            marksChanged = true;
                        } else if (Topics.AI_DECISIONS.equals(record.topic())) {
                            onDecision(AvroCodec.decode(record.value(), AiDecision.class));
                        }
                    } catch (RuntimeException e) {
                        log.warn("skipping undecodable record on {}: {}", record.topic(), e.getMessage());
                    }
                }
                long now = System.currentTimeMillis();
                if (marksChanged) {
                    sse.broadcast("marks", markState.snapshot(now));
                }
                if (now - lastRulesRun >= RULES_EVERY_MILLIS) {
                    rules.evaluate(markState.snapshot(now), now);
                    sse.broadcast("attention", feed.snapshot());
                    lastRulesRun = now;
                }
            }
        } catch (Exception e) {
            if (running.get()) {
                log.error("ui-gateway consumer died: {} — UI will not update (restart the app once the broker is up)",
                        e.getMessage());
            }
        }
    }

    private void onMark(MarkEvent event) {
        String instrumentId = event.getInstrumentId().toString();
        String price = event.getPrice().toPlainString();
        long providerMillis = event.getMeta().getProviderTimestamp().toEpochMilli();
        markState.update(event.getInstrumentId(), price, event.getSource(), providerMillis);
        markHistory.record(instrumentId, price, providerMillis);
        // Diagnostic: prove history is filling. Logs about every ~20s of marks.
        long n = marksConsumed.incrementAndGet();
        if (n % 180 == 0) {
            log.info("ui-gateway: consumed {} md.marks; history {} instruments, {} pts for {}",
                    n, markHistory.instrumentCount(), markHistory.pointCount(instrumentId), instrumentId);
        }
    }

    private void onDecision(AiDecision decision) {
        String body;
        try {
            var actions = JSON.readTree(decision.getProposedActionsJson());
            body = actions.path("text").asText(decision.getProposedActionsJson());
        } catch (Exception e) {
            body = decision.getProposedActionsJson();
        }
        feed.upsert(new AttentionFeed.AttentionItem(
                "ai:" + decision.getDecisionId(),
                decision.getMeta().getIngestTimestamp().toEpochMilli(),
                AttentionFeed.Severity.INFO,
                "ai-commentary",
                "Market commentary (" + decision.getModelId() + ")",
                body,
                "ai.decisions/" + decision.getDecisionId()));
        sse.broadcast("attention", feed.snapshot());
    }

    @Override
    public void close() {
        running.set(false);
        Thread thread = consumerThread;
        if (thread != null) {
            try {
                thread.join(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
