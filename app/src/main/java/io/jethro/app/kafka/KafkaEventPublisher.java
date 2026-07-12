package io.jethro.app.kafka;

import io.jethro.messaging.AvroCodec;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.avro.specific.SpecificRecordBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Async Avro-binary producer for platform topics (ADR-0012). Idempotent producer on
 * (free duplicate reduction) but correctness rests on consumer idempotency, never on
 * broker guarantees (invariant 8). Publish failures are counted and logged — the
 * market path must keep running when the broker is down.
 */
public final class KafkaEventPublisher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    private final KafkaProducer<String, byte[]> producer;
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    public KafkaEventPublisher(String bootstrapServers) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        props.put(ProducerConfig.LINGER_MS_CONFIG, "20");
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "10000");
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000");
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "2000"); // never wedge callers on a dead broker
        this.producer = new KafkaProducer<>(props, new StringSerializer(), new ByteArraySerializer());
    }

    public void publish(String topic, String key, SpecificRecordBase event) {
        try {
            producer.send(new ProducerRecord<>(topic, key, AvroCodec.encode(event)), (metadata, exception) -> {
                if (exception != null) {
                    long failures = failed.incrementAndGet();
                    if (failures == 1 || failures % 100 == 0) {
                        log.warn("publish to {} failing ({} total): {}", topic, failures, exception.getMessage());
                    }
                } else {
                    published.incrementAndGet();
                }
            });
        } catch (Exception e) {
            long failures = failed.incrementAndGet();
            if (failures == 1 || failures % 100 == 0) {
                log.warn("publish to {} rejected ({} total): {}", topic, failures, e.getMessage());
            }
        }
    }

    public long publishedCount() {
        return published.get();
    }

    public long failedCount() {
        return failed.get();
    }

    @Override
    public void close() {
        producer.close();
    }
}
