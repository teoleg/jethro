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
 * Feeds {@link RiskProjection} from the {@code fills} table (boot rebuild) and the
 * {@code fills} / {@code md.marks} topics (live increments) — invariant 3: positions are
 * projected from fills.
 *
 * <p><b>Boot rebuild from the TABLE, not the topic.</b> The in-memory projection is derived
 * and must be rebuilt on every boot. It seeds from the Postgres {@code fills} table
 * ({@link FillHistorySource}) — the never-expiring source of truth — and then consumes the
 * fills topic from LATEST for live increments. Replaying the topic from the beginning
 * instead would silently truncate history once the platform outlives the topic's retention
 * window (unconfigured ⇒ broker default ~7 days), rebuilding wrong positions with no error.
 * The projection dedupes on fillId (invariant 6), so any fill present in both the seed and
 * a live topic record is applied once.
 *
 * <p>Own consumer group — cross-module data via topics (ADR-0015). Undecodable records are
 * counted and logged, never silently dropped.
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
    private final FillHistorySource fillHistory; // boot rebuild source (the fills table)

    public RiskDataConsumer(String bootstrapServers, RiskProjection projection,
                            CurveService curveService, TreasuryCurveView treasuryCurve) {
        this(bootstrapServers, projection, curveService, treasuryCurve, null, FillHistorySource.NONE);
    }

    /** @param fillTap called after each fill is applied to the projection (invariant 6: taps
     *                 must be idempotent — a fill can arrive from the boot seed and the topic).
     *  @param fillHistory the fills table to seed the projection from at boot (source of truth). */
    public RiskDataConsumer(String bootstrapServers, RiskProjection projection,
                            CurveService curveService, TreasuryCurveView treasuryCurve,
                            java.util.function.Consumer<Fill> fillTap, FillHistorySource fillHistory) {
        this.bootstrapServers = bootstrapServers;
        this.projection = projection;
        this.curveService = curveService;
        this.treasuryCurve = treasuryCurve;
        this.fillTap = fillTap;
        this.fillHistory = fillHistory != null ? fillHistory : FillHistorySource.NONE;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        seedFromFillsTable(); // rebuild positions from the source of truth BEFORE going live
        Thread t = new Thread(this::consumeLoop, "risk-pnl-consumer");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    /** Rebuilds the projection from the persisted fill history (invariant 3). Runs before the
     *  topic consumer so live increments layer on top; the projection dedupes any overlap.
     *  Package-visible for the seed test (no Kafka needed). */
    void seedFromFillsTable() {
        try {
            List<Fill> history = fillHistory.allFills();
            for (Fill fill : history) {
                projection.applyFill(fill);
                if (fillTap != null) {
                    fillTap.accept(fill);
                }
            }
            if (!history.isEmpty()) {
                log.info("risk projection seeded from {} persisted fills (source of truth); "
                        + "consuming the fills topic from latest for live increments", history.size());
            }
        } catch (Exception e) {
            // Never block startup on the seed; the topic still carries recent fills as a
            // degraded fallback. Loud, because positions may be incomplete until it recovers.
            log.error("could not seed risk projection from the fills table ({}); positions may be "
                    + "incomplete until the table is reachable", e.toString());
        }
    }

    private void consumeLoop() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        // The projection was already seeded from the fills TABLE (the source of truth) before
        // this thread started, so both topics are consumed from the END — fills for live
        // increments only (NOT a full replay, which would truncate past the topic's retention
        // window), marks because only the latest matters. Ephemeral group: no committed
        // offsets, seek explicitly on assignment.
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "risk-pnl-" + UUID.randomUUID());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        try (var consumer = new KafkaConsumer<>(props, new StringDeserializer(), new ByteArrayDeserializer())) {
            consumer.subscribe(List.of(Topics.resolved(Topics.FILLS), Topics.resolved(Topics.MD_MARKS)), new ConsumerRebalanceListener() {
                @Override
                public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                }

                @Override
                public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                    // Both from the END: the projection was seeded from the fills table already,
                    // so the topic supplies only live increments (no retention-truncated replay).
                    consumer.seekToEnd(partitions);
                }
            });
            while (running.get()) {
                var records = consumer.poll(Duration.ofMillis(500));
                for (var record : records) {
                    try {
                        if (Topics.resolved(Topics.FILLS).equals(record.topic())) {
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
