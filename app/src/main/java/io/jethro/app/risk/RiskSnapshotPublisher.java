package io.jethro.app.risk;

import io.jethro.app.kafka.KafkaEventPublisher;
import io.jethro.domain.Decimals;
import io.jethro.messaging.EventMeta;
import io.jethro.messaging.RiskSnapshot;
import io.jethro.messaging.Topics;
import io.jethro.trading.riskpnl.ConsolidatedRisk;
import io.jethro.trading.riskpnl.RiskProjection;
import org.springframework.context.SmartLifecycle;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Publishes a per-book {@link RiskSnapshot} to {@code risk.snapshots} at ~1Hz (the
 * conflation point, ADR-0014), keyed by bookId. Exposures/PnL are restated at the
 * schema's fixed decimal scale (invariant 1). Best-effort: a broker hiccup must not stop
 * the projection updating, so publish failures are swallowed by {@link KafkaEventPublisher}.
 */
public final class RiskSnapshotPublisher implements SmartLifecycle {

    private final RiskProjection projection;
    private final KafkaEventPublisher publisher;
    private volatile ScheduledExecutorService scheduler;

    public RiskSnapshotPublisher(RiskProjection projection, KafkaEventPublisher publisher) {
        this.projection = projection;
        this.publisher = publisher;
    }

    @Override
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "risk-snapshot-publisher");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::publishSnapshot, 1, 1, TimeUnit.SECONDS);
    }

    private void publishSnapshot() {
        long now = System.currentTimeMillis();
        ConsolidatedRisk risk = projection.snapshot(now);
        for (ConsolidatedRisk.Group book : risk.byBook()) {
            long maxMarkAge = risk.positions().stream()
                    .filter(p -> p.bookId().equals(book.key()) && p.hasMark())
                    .mapToLong(p -> p.markAgeMillis())
                    .max().orElse(0);
            Instant ts = Instant.ofEpochMilli(now);
            publisher.publish(Topics.RISK_SNAPSHOTS, book.key(), RiskSnapshot.newBuilder()
                    .setMeta(EventMeta.newBuilder()
                            .setEventId(UUID.randomUUID().toString())
                            .setProviderTimestamp(ts)
                            .setIngestTimestamp(ts)
                            .build())
                    .setBookId(book.key())
                    .setCurrency(book.currency())
                    .setRealizedPnl(Decimals.atScale(book.realizedPnl(), 8))
                    .setUnrealizedPnl(Decimals.atScale(book.unrealizedPnl(), 8))
                    .setGrossExposure(Decimals.atScale(book.grossExposure(), 8))
                    .setNetExposure(Decimals.atScale(book.netExposure(), 8))
                    .setMaxMarkAgeMillis(maxMarkAge)
                    .build());
        }
    }

    @Override
    public void stop() {
        var s = scheduler;
        if (s != null) {
            s.shutdownNow();
            scheduler = null;
        }
    }

    @Override
    public boolean isRunning() {
        return scheduler != null;
    }
}
