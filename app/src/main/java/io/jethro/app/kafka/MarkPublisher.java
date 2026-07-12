package io.jethro.app.kafka;

import io.jethro.app.trading.TradingCoreLifecycle;
import io.jethro.messaging.EventMeta;
import io.jethro.messaging.MarkEvent;
import io.jethro.messaging.Topics;
import org.springframework.context.SmartLifecycle;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The md.marks conflation point (ADR-0014): ~1Hz last-value snapshot from the mark
 * cache onto the broker — raw ticks never leave the process. Warm/stale marks are not
 * republished as fresh.
 */
public final class MarkPublisher implements SmartLifecycle {

    private final KafkaEventPublisher publisher;
    private final TradingCoreLifecycle tradingCore;
    private volatile ScheduledExecutorService scheduler;

    public MarkPublisher(KafkaEventPublisher publisher, TradingCoreLifecycle tradingCore) {
        this.publisher = publisher;
        this.tradingCore = tradingCore;
    }

    @Override
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mark-publisher");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::publishSnapshot, 1, 1, TimeUnit.SECONDS);
    }

    private void publishSnapshot() {
        var runtime = tradingCore.runtime();
        if (runtime == null) {
            return;
        }
        Instant now = Instant.now();
        for (var mark : runtime.markCache().snapshot()) {
            if (mark.stale()) {
                continue;
            }
            publisher.publish(Topics.MD_MARKS, mark.instrumentId(), MarkEvent.newBuilder()
                    .setMeta(EventMeta.newBuilder()
                            .setEventId(UUID.randomUUID().toString())
                            .setProviderTimestamp(mark.providerTimestamp())
                            .setIngestTimestamp(now)
                            .build())
                    .setInstrumentId(mark.instrumentId())
                    .setPrice(mark.price())
                    .setSource(mark.source())
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
