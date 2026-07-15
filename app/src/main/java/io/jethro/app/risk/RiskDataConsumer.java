package io.jethro.app.risk;

import io.jethro.domain.BookId;
import io.jethro.domain.Fill;
import io.jethro.domain.InstrumentId;
import io.jethro.domain.Side;
import io.jethro.messaging.AvroCodec;
import io.jethro.messaging.FillEvent;
import io.jethro.messaging.MarkEvent;
import io.jethro.messaging.Topics;
import io.jethro.trading.riskpnl.CurveService;
import io.jethro.trading.riskpnl.TreasuryCurveView;
import io.jethro.trading.riskpnl.RiskProjection;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
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
    private final CurveService curveService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong skipped = new AtomicLong();
    private volatile Thread thread;

    private final TreasuryCurveView treasuryCurve;
    private final java.util.function.Consumer<Fill> fillTap; // nullable: swap-trade registry etc.

    public RiskDataConsumer(String bootstrapServers, RiskProjection projection,
                            CurveService curveService, TreasuryCurveView treasuryCurve) {
        this(bootstrapServers, projection, curveService, treasuryCurve, null);
    }

    /** @param fillTap called after each fill is applied to the projection (replayed from the
     *                 beginning on every boot — taps must be idempotent, invariant 6). */
    public RiskDataConsumer(String bootstrapServers, RiskProjection projection,
                            CurveService curveService, TreasuryCurveView treasuryCurve,
                            java.util.function.Consumer<Fill> fillTap) {
        this.bootstrapServers = bootstrapServers;
        this.projection = projection;
        this.curveService = curveService;
        this.treasuryCurve = treasuryCurve;
        this.fillTap = fillTap;
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
        // The projection is in-memory and derived, so it must be rebuilt from scratch on
        // every boot: use an ephemeral group (no committed offsets to resume from) and
        // seek explicitly on assignment — fills from the beginning to replay the full
        // position history (invariant 3), marks from the end since only the latest matters.
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "risk-pnl-" + UUID.randomUUID());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (var consumer = new KafkaConsumer<>(props, new StringDeserializer(), new ByteArrayDeserializer())) {
            consumer.subscribe(List.of(Topics.FILLS, Topics.MD_MARKS), new ConsumerRebalanceListener() {
                @Override
                public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                }

                @Override
                public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                    var fills = partitions.stream().filter(p -> Topics.FILLS.equals(p.topic())).toList();
                    var marks = partitions.stream().filter(p -> Topics.MD_MARKS.equals(p.topic())).toList();
                    if (!fills.isEmpty()) {
                        consumer.seekToBeginning(fills);
                    }
                    if (!marks.isEmpty()) {
                        consumer.seekToEnd(marks);
                    }
                }
            });
            while (running.get()) {
                var records = consumer.poll(Duration.ofMillis(500));
                for (var record : records) {
                    try {
                        if (Topics.FILLS.equals(record.topic())) {
                            Fill fill = toFill(AvroCodec.decode(record.value(), FillEvent.class));
                            projection.applyFill(fill);
                            if (fillTap != null) {
                                fillTap.accept(fill);
                            }
                        } else {
                            MarkEvent mark = AvroCodec.decode(record.value(), MarkEvent.class);
                            String id = mark.getInstrumentId().toString();
                            if (CurveService.isCurveQuote(id)) {
                                curveService.onRate(id, mark.getPrice()); // curve, not a position mark
                            } else if (TreasuryCurveView.isTsyQuote(id)) {
                                treasuryCurve.onRate(id, mark.getPrice()); // TSY par curve (distinct)
                            } else {
                                projection.applyMark(id, mark.getPrice(),
                                        mark.getMeta().getIngestTimestamp().toEpochMilli());
                            }
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
                e.getFee() != null ? e.getFee() : java.math.BigDecimal.ZERO, // pre-fee events = 0
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
