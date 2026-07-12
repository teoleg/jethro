package io.jethro.trading.runtime;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Single-producer/single-consumer ring buffer for the market path (ADR-0014).
 * Pre-allocated mutable slots: publishing and consuming allocate nothing.
 *
 * <p>Drop policy v0: when the buffer is full the incoming tick is dropped and
 * counted (invariant 9: droppable, never silent). Freshness is preserved by the
 * consumer-side {@link MarkCache} conflation. Revisit to overwrite-oldest if
 * consumer stalls become a measured problem.
 */
public final class TickRingBuffer {

    /** Mutable slot; valid only inside the consumer's visit callback. */
    public static final class TickSlot {
        String instrumentId;
        long priceScaled;
        long qtyScaled;
        long providerTimestampMillis;
        long ingestTimestampMillis;

        public String instrumentId() {
            return instrumentId;
        }

        public long priceScaled() {
            return priceScaled;
        }

        public long qtyScaled() {
            return qtyScaled;
        }

        public long providerTimestampMillis() {
            return providerTimestampMillis;
        }

        public long ingestTimestampMillis() {
            return ingestTimestampMillis;
        }
    }

    @FunctionalInterface
    public interface TickVisitor {
        void visit(TickSlot slot);
    }

    private final TickSlot[] slots;
    private final int mask;
    private final AtomicLong writeSequence = new AtomicLong();
    private final AtomicLong readSequence = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    public TickRingBuffer(int capacityPowerOfTwo) {
        if (Integer.bitCount(capacityPowerOfTwo) != 1) {
            throw new IllegalArgumentException("capacity must be a power of two");
        }
        this.slots = new TickSlot[capacityPowerOfTwo];
        for (int i = 0; i < capacityPowerOfTwo; i++) {
            slots[i] = new TickSlot();
        }
        this.mask = capacityPowerOfTwo - 1;
    }

    /** Producer side (feed thread). Returns false and counts the drop when full. */
    public boolean offer(String instrumentId, long priceScaled, long qtyScaled,
                         long providerTimestampMillis, long ingestTimestampMillis) {
        long write = writeSequence.get();
        if (write - readSequence.get() >= slots.length) {
            dropped.incrementAndGet();
            return false;
        }
        TickSlot slot = slots[(int) (write & mask)];
        slot.instrumentId = instrumentId;
        slot.priceScaled = priceScaled;
        slot.qtyScaled = qtyScaled;
        slot.providerTimestampMillis = providerTimestampMillis;
        slot.ingestTimestampMillis = ingestTimestampMillis;
        writeSequence.lazySet(write + 1); // release: slot writes visible before sequence
        return true;
    }

    /** Consumer side (core loop thread). Returns false when empty. */
    public boolean poll(TickVisitor visitor) {
        long read = readSequence.get();
        if (read >= writeSequence.get()) {
            return false;
        }
        visitor.visit(slots[(int) (read & mask)]);
        readSequence.lazySet(read + 1);
        return true;
    }

    public long droppedCount() {
        return dropped.get();
    }
}
