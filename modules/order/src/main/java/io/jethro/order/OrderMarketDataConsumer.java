package io.jethro.order;

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
 * Feeds {@link LastPriceCache} from md.marks so simulated execution can price fills.
 * Its own consumer group (independent of ui-gateway) — cross-module data via topics
 * (ADR-0015). Duplicate delivery is idempotent here: last-value put by instrument.
 */
public final class OrderMarketDataConsumer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OrderMarketDataConsumer.class);

    private final String bootstrapServers;
    private final LastPriceCache prices;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread thread;

    public OrderMarketDataConsumer(String bootstrapServers, LastPriceCache prices) {
        this.bootstrapServers = bootstrapServers;
        this.prices = prices;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(this::consumeLoop, "order-marketdata-consumer");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    private void consumeLoop() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "order-marketdata");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        try (var consumer = new KafkaConsumer<>(props, new StringDeserializer(), new ByteArrayDeserializer())) {
            consumer.subscribe(List.of(Topics.MD_MARKS));
            while (running.get()) {
                var records = consumer.poll(Duration.ofMillis(500));
                for (var record : records) {
                    try {
                        MarkEvent mark = AvroCodec.decode(record.value(), MarkEvent.class);
                        prices.update(mark.getInstrumentId().toString(), mark.getPrice());
                    } catch (RuntimeException e) {
                        log.warn("skipping undecodable mark: {}", e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            if (running.get()) {
                log.error("order market-data consumer died: {} — simulated fills will lack prices",
                        e.getMessage());
            }
        }
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
