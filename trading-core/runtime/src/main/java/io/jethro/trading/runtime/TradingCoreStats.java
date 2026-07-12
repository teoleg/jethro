package io.jethro.trading.runtime;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters for the market path. Drops and gaps are always counted and exposed —
 * never silent (invariant 9).
 */
public final class TradingCoreStats {

    private final AtomicLong ticksIn = new AtomicLong();
    private final AtomicLong marksFlushed = new AtomicLong();

    private final TickRingBuffer buffer;

    public TradingCoreStats(TickRingBuffer buffer) {
        this.buffer = buffer;
    }

    void tickConsumed() {
        ticksIn.incrementAndGet();
    }

    void marksFlushed(long count) {
        marksFlushed.addAndGet(count);
    }

    public long ticksIn() {
        return ticksIn.get();
    }

    public long ticksDropped() {
        return buffer.droppedCount();
    }

    public long marksFlushedTotal() {
        return marksFlushed.get();
    }
}
