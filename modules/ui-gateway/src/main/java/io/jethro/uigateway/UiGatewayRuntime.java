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
    private final long marksReplayWindowMillis;
    private final AttentionFeed feed;
    private final AttentionRules rules;
    private final SseBroadcaster sse;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicLong marksConsumed = new java.util.concurrent.atomic.AtomicLong();
    private volatile Thread consumerThread;

    public UiGatewayRuntime(String bootstrapServers, MarkState markState, MarkHistory markHistory,
                            long marksReplayWindowMillis, AttentionFeed feed, AttentionRules rules,
                            SseBroadcaster sse) {
        this.bootstrapServers = bootstrapServers;
        this.markState = markState;
        this.markHistory = markHistory;
        this.marksReplayWindowMillis = marksReplayWindowMillis;
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
        // UI state is derived, so rebuild it from the log on every boot: ephemeral group (no
        // committed offsets). The price-history chart is now durable in LMDB (LmdbMarkHistory),
        // so md.marks is replayed only over a short window to refresh the current-price snapshot
        // (MarkState) — not the whole retention, which kept boot slow for no gain: this app is
        // the sole producer of marks in every mode, so a restart never has "missed" marks to
        // backfill. ai.decisions is read from the end (old commentary isn't re-surfaced).
        // Duplicate delivery is naturally idempotent here (last-value upsert by key) — invariant 8.
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "ui-gateway-" + java.util.UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        long lastRulesRun = 0;
        try (var consumer = new KafkaConsumer<>(props, new StringDeserializer(), new ByteArrayDeserializer())) {
            consumer.subscribe(List.of(Topics.resolved(Topics.MD_MARKS), Topics.resolved(Topics.AI_DECISIONS)),
                    new org.apache.kafka.clients.consumer.ConsumerRebalanceListener() {
                        @Override
                        public void onPartitionsRevoked(
                                java.util.Collection<org.apache.kafka.common.TopicPartition> partitions) {
                        }

                        @Override
                        public void onPartitionsAssigned(
                                java.util.Collection<org.apache.kafka.common.TopicPartition> partitions) {
                            seekForReplay(consumer, partitions);
                        }
                    });
            log.info("ui-gateway consuming {} (replaying last {}min to refresh snapshot; history durable in LMDB) and {} from {}",
                    Topics.resolved(Topics.MD_MARKS), marksReplayWindowMillis / 60_000, Topics.resolved(Topics.AI_DECISIONS), bootstrapServers);
            while (running.get()) {
                var records = consumer.poll(POLL);
                boolean marksChanged = false;
                for (var record : records) {
                    try {
                        if (Topics.resolved(Topics.MD_MARKS).equals(record.topic())) {
                            onMark(AvroCodec.decode(record.value(), MarkEvent.class));
                            marksChanged = true;
                        } else if (Topics.resolved(Topics.AI_DECISIONS).equals(record.topic())) {
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

    /** md.marks → back a short replay window (refresh the current-price snapshot; history is
     *  durable in LMDB); ai.decisions → end. */
    private void seekForReplay(KafkaConsumer<String, byte[]> consumer,
                               java.util.Collection<org.apache.kafka.common.TopicPartition> partitions) {
        var marks = partitions.stream().filter(p -> Topics.resolved(Topics.MD_MARKS).equals(p.topic())).toList();
        var decisions = partitions.stream().filter(p -> Topics.resolved(Topics.AI_DECISIONS).equals(p.topic())).toList();
        if (!decisions.isEmpty()) {
            consumer.seekToEnd(decisions);
        }
        if (marks.isEmpty()) {
            return;
        }
        long since = System.currentTimeMillis() - marksReplayWindowMillis;
        var query = new java.util.HashMap<org.apache.kafka.common.TopicPartition, Long>();
        marks.forEach(p -> query.put(p, since));
        var offsets = consumer.offsetsForTimes(query);
        for (var p : marks) {
            var offset = offsets.get(p);
            if (offset != null) {
                consumer.seek(p, offset.offset());
            } else {
                consumer.seekToBeginning(List.of(p)); // topic shorter than the window
            }
        }
    }

    private void onMark(MarkEvent event) {
        String instrumentId = event.getInstrumentId().toString();
        String price = event.getPrice().toPlainString();
        long providerMillis = event.getMeta().getProviderTimestamp().toEpochMilli();
        markState.update(instrumentId, price,
                event.getBid() != null ? event.getBid().toPlainString() : null,
                event.getAsk() != null ? event.getAsk().toPlainString() : null,
                event.getSource(), providerMillis);
        markHistory.record(instrumentId, price, providerMillis);
        // Diagnostic: prove history is filling. Logs about every ~20s of marks.
        long n = marksConsumed.incrementAndGet();
        if (n % 180 == 0) {
            log.info("ui-gateway: consumed {} md.marks; history {} instruments, {} pts for {}",
                    n, markHistory.instrumentCount(), markHistory.pointCount(instrumentId), instrumentId);
        }
    }

    private void onDecision(AiDecision decision) {
        // ai.decisions carries every audited AI run (commentary, hypotheses, ...). Only the
        // risk commentator's narration renders as a commentary card here; other decision types
        // (e.g. hypotheses) are surfaced by their own producers — don't dump their raw JSON.
        String body;
        try {
            var actions = JSON.readTree(decision.getProposedActionsJson());
            if (!"commentary".equals(actions.path("type").asText(""))) {
                return;
            }
            body = actions.path("text").asText("");
        } catch (Exception e) {
            return; // unparseable — it's still audited on the topic, just not surfaced
        }
        if (body.isBlank()) {
            return;
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
