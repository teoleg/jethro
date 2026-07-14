package io.jethro.trading.runtime;

import io.jethro.trading.marketdata.MarketDataAdapter;
import io.jethro.trading.marketdata.MarketDataListener;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * The market-path core loop (ADR-0014): feed adapter → ring buffer → mark cache,
 * all in-process. Owns the single feed session, the consumer thread, and the LMDB
 * warm/dedupe store. Downstream consumers (algo, risk) hook in behind the same
 * loop in later build steps.
 */
public final class TradingCoreRuntime implements AutoCloseable {

    private static final long IDLE_PARK_NANOS = 50_000; // 50µs when the buffer is empty
    private static final long MARK_FLUSH_INTERVAL_MILLIS = 1_000;

    private final MarketDataAdapter adapter;
    private final TickRingBuffer buffer;
    private final MarkCache markCache;
    private final LmdbStateStore stateStore; // nullable: runtime works without persistence
    private final TradingCoreStats stats;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread consumerThread;

    public TradingCoreRuntime(MarketDataAdapter adapter, int bufferCapacity, LmdbStateStore stateStore) {
        this(adapter, bufferCapacity, stateStore, MarkCache.JumpThresholds.DISABLED);
    }

    /** With a mark-jump guard: implausible single-update moves quarantine the instrument
     *  (corporate action / bad print — see {@link MarkCache}). */
    public TradingCoreRuntime(MarketDataAdapter adapter, int bufferCapacity, LmdbStateStore stateStore,
                              MarkCache.JumpThresholds thresholds) {
        this.adapter = adapter;
        this.buffer = new TickRingBuffer(bufferCapacity);
        this.markCache = new MarkCache(thresholds);
        this.stateStore = stateStore;
        this.stats = new TradingCoreStats(buffer);
    }

    /** Warm-loads marks (stale until refreshed), starts the consumer loop and the feed. */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("trading-core runtime already started");
        }
        if (stateStore != null) {
            stateStore.forEachMark((instrumentId, priceScaled, providerTs, source) -> {
                markCache.loadStale(instrumentId, priceScaled, providerTs, source);
                stats.markWarmLoaded();
            });
        }
        // Non-daemon by design: the market path IS the application — it keeps the
        // JVM alive until the context shuts it down.
        Thread thread = new Thread(this::consumeLoop, "trading-core");
        thread.setDaemon(false);
        consumerThread = thread;
        thread.start();
        adapter.start(feedListener());
    }

    private MarketDataListener feedListener() {
        // Producer side: runs on the feed thread, writes into the ring buffer only.
        return (instrumentId, priceScaled, qtyScaled, providerTs, ingestTs) ->
                buffer.offer(instrumentId, priceScaled, qtyScaled, providerTs, ingestTs);
    }

    private void consumeLoop() {
        String source = adapter.name();
        long lastFlushMillis = System.currentTimeMillis();
        TickRingBuffer.TickVisitor visitor = slot -> {
            markCache.update(slot.instrumentId(), slot.priceScaled(),
                    slot.providerTimestampMillis(), slot.ingestTimestampMillis(), source);
            stats.tickConsumed();
        };
        while (running.get()) {
            if (!buffer.poll(visitor)) {
                LockSupport.parkNanos(IDLE_PARK_NANOS);
            }
            long now = System.currentTimeMillis();
            if (stateStore != null && now - lastFlushMillis >= MARK_FLUSH_INTERVAL_MILLIS) {
                flushMarks();
                lastFlushMillis = now;
            }
        }
    }

    private void flushMarks() {
        // Off the hot path only in the sense that it runs at 1Hz; still on the loop
        // thread by design — single writer to LMDB (matches its single-writer model).
        var snapshots = markCache.snapshot();
        for (var mark : snapshots) {
            if (!mark.stale()) { // never persist a warm-loaded mark as if it were fresh
                stateStore.putMark(mark.instrumentId(),
                        io.jethro.domain.Decimals.toScaledLong(mark.price(), io.jethro.domain.Decimals.PRICE_SCALE),
                        mark.providerTimestamp().toEpochMilli(),
                        mark.source());
            }
        }
        stats.marksFlushed(snapshots.size());
    }

    public MarkCache markCache() {
        return markCache;
    }

    public TradingCoreStats stats() {
        return stats;
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        adapter.stop();
        Thread thread = consumerThread;
        if (thread != null) {
            try {
                thread.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (stateStore != null) {
            flushMarks();
            stateStore.close();
        }
    }
}
