package io.jethro.trading.runtime;

import io.jethro.domain.Decimals;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Last-value mark per instrument — the conflation point of the market path
 * (ADR-0014). Hot-path updates are scaled-long writes into a pre-existing holder;
 * one allocation per instrument lifetime, none per tick. Marks loaded from the warm
 * cache are stale until the live feed refreshes them (invariant 4).
 */
public final class MarkCache {

    /** Mutable per-instrument holder. Fields volatile: written by core loop, read anywhere. */
    public static final class MarkHolder {
        private volatile long priceScaled;
        private volatile long providerTimestampMillis;
        private volatile long ingestTimestampMillis;
        private volatile String source;
        private volatile boolean stale;

        public long priceScaled() {
            return priceScaled;
        }

        public long providerTimestampMillis() {
            return providerTimestampMillis;
        }

        public boolean stale() {
            return stale;
        }

        public String source() {
            return source;
        }
    }

    /** Immutable boundary view (BigDecimal per invariant 1 at boundaries). */
    public record MarkSnapshot(String instrumentId, BigDecimal price, String source,
                               Instant providerTimestamp, Instant ingestTimestamp, boolean stale) {
    }

    private final ConcurrentHashMap<String, MarkHolder> marks = new ConcurrentHashMap<>();

    /** Hot path: live update from the core loop. */
    public void update(String instrumentId, long priceScaled,
                       long providerTimestampMillis, long ingestTimestampMillis, String source) {
        MarkHolder holder = marks.computeIfAbsent(instrumentId, k -> new MarkHolder());
        holder.priceScaled = priceScaled;
        holder.providerTimestampMillis = providerTimestampMillis;
        holder.ingestTimestampMillis = ingestTimestampMillis;
        holder.source = source;
        holder.stale = false;
    }

    /** Warm-restart load (LMDB): flagged stale until the feed refreshes (invariant 4). */
    public void loadStale(String instrumentId, long priceScaled, long providerTimestampMillis, String source) {
        MarkHolder holder = marks.computeIfAbsent(instrumentId, k -> new MarkHolder());
        holder.priceScaled = priceScaled;
        holder.providerTimestampMillis = providerTimestampMillis;
        holder.ingestTimestampMillis = providerTimestampMillis;
        holder.source = source;
        holder.stale = true;
    }

    public MarkHolder get(String instrumentId) {
        return marks.get(instrumentId);
    }

    public int size() {
        return marks.size();
    }

    /** Boundary snapshot for UI/risk/persistence — allocates; never call per tick. */
    public List<MarkSnapshot> snapshot() {
        List<MarkSnapshot> result = new ArrayList<>(marks.size());
        marks.forEach((id, h) -> result.add(new MarkSnapshot(
                id,
                Decimals.fromScaledLong(h.priceScaled, Decimals.PRICE_SCALE),
                h.source,
                Instant.ofEpochMilli(h.providerTimestampMillis),
                Instant.ofEpochMilli(h.ingestTimestampMillis),
                h.stale)));
        return result;
    }
}
