package io.jethro.trading.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TickRingBufferTest {

    @Test
    void preservesOrder() {
        var buffer = new TickRingBuffer(8);
        for (int i = 0; i < 5; i++) {
            assertTrue(buffer.offer("I" + i, i * 100L, 1_000_000L, i, i));
        }
        List<String> seen = new ArrayList<>();
        while (buffer.poll(slot -> seen.add(slot.instrumentId() + ":" + slot.priceScaled()))) {
            // drain
        }
        assertEquals(List.of("I0:0", "I1:100", "I2:200", "I3:300", "I4:400"), seen);
    }

    @Test
    void dropsAndCountsWhenFull() {
        var buffer = new TickRingBuffer(4);
        for (int i = 0; i < 4; i++) {
            assertTrue(buffer.offer("I", i, 1, i, i));
        }
        assertFalse(buffer.offer("I", 99, 1, 99, 99)); // full: dropped, counted
        assertEquals(1, buffer.droppedCount());

        // consume one, capacity frees up
        assertTrue(buffer.poll(slot -> { }));
        assertTrue(buffer.offer("I", 100, 1, 100, 100));
        assertEquals(1, buffer.droppedCount());
    }

    @Test
    void producerConsumerThreadsLoseNothingWithinCapacity() throws Exception {
        var buffer = new TickRingBuffer(1024);
        int total = 100_000;
        var consumed = new java.util.concurrent.atomic.AtomicLong();
        var lastSeen = new java.util.concurrent.atomic.AtomicLong(-1);
        var ordered = new java.util.concurrent.atomic.AtomicBoolean(true);

        Thread consumer = new Thread(() -> {
            while (consumed.get() < total) {
                buffer.poll(slot -> {
                    if (slot.priceScaled() <= lastSeen.get()) {
                        ordered.set(false);
                    }
                    lastSeen.set(slot.priceScaled());
                    consumed.incrementAndGet();
                });
            }
        });
        consumer.start();

        for (long i = 0; i < total; i++) {
            while (!buffer.offer("I", i, 1, i, i)) {
                Thread.onSpinWait(); // backpressure: retry instead of losing the tick
            }
        }
        consumer.join(10_000);

        // Every published tick arrives exactly once and in order. droppedCount is not
        // asserted: each failed offer above counts as a drop by design (in production
        // the feed thread never retries — a failed offer IS a lost tick).
        assertEquals(total, consumed.get());
        assertTrue(ordered.get(), "ticks must be consumed in publish order");
    }
}
