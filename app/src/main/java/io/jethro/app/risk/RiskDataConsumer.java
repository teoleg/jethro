package io.jethro.app.risk;

import io.jethro.domain.BookId;
import io.jethro.domain.Fill;
import io.jethro.domain.InstrumentId;
import io.jethro.domain.Side;
import io.jethro.messaging.AvroCodec;
import io.jethro.messaging.FillEvent;
import io.jethro.messaging.MarkEvent;
import io.jethro.messaging.Topics;
import io.jethro.trading.riskpnl.RiskProjection;
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
import java.util.concurrent.atomic.AtomicLong;

/**
 * Feeds {@link RiskProjection} from the {@code fills} and {@code md.marks} topics
 * (invariant 3: positions are projected from fills). Own consumer group — cross-module
 * data via topics (ADR-0015). Idempotency lives in the projection (dedupe on fillId,
 * invariant 6); undecodable records are counted and logged, never silently dropped.
 */
public final class RiskDataConsumer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RiskDataConsumer.class);

    private final String bootstrapServers;
    private final RiskProjection projection;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong skipped = new AtomicLong();
    private volatile Thread thread;

    public RiskDataConsumer(String bootstrapServers, RiskProjection projection) {
        this.bootstrapServers = bootstrapServers;
        this.projection = projection;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(this::consumeLoop, "risk-pnl-consumer");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    private void consumeLoop() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "risk-pnl");
        // fills from the beginning (positions must be complete); marks only need latest.
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (var consumer = new KafkaConsumer<>(props, new StringDeserializer(), new ByteArrayDeserializer())) {
            consumer.subscribe(List.of(Topics.FILLS, Topics.MD_MARKS));
            while (running.get()) {
                var records = consumer.poll(Duration.ofMillis(500));
                for (var record : records) {
                    try {
                        if (Topics.FILLS.equals(record.topic())) {
                            projection.applyFill(toFill(AvroCodec.decode(record.value(), FillEvent.class)));
                        } else {
                            MarkEvent mark = AvroCodec.decode(record.value(), MarkEvent.class);
                            projection.applyMark(mark.getInstrumentId().toString(), mark.getPrice(),
                                    mark.getMeta().getIngestTimestamp().toEpochMilli());
                        }
                    } catch (RuntimeException e) {
                        log.warn("skipping undecodable {} record ({} total): {}",
                                record.topic(), skipped.incrementAndGet(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            if (running.get()) {
                log.error("risk-pnl consumer died: {} — risk/PnL will stop updating", e.getMessage());
            }
        }
    }

    private static Fill toFill(FillEvent e) {
        return new Fill(
                e.getFillId().toString(),
                e.getOrderId().toString(),
                new BookId(e.getBookId().toString()),
                new InstrumentId(e.getInstrumentId().toString()),
                Side.valueOf(e.getSide().name()),
                e.getQuantity(),
                e.getPrice(),
                e.getMeta().getIngestTimestamp());
    }

    @Override
    public void close() {
        running.set(false);
        Thread t = thread;
        if (t != null) {
            try {
                t.join(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
