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
 *
 * <p><b>Corporate-action / bad-print guard:</b> a single update that jumps more than the
 * instrument's configured threshold (bps of the previous mark) QUARANTINES the instrument:
 * the suspect price never enters the cache (so it never reaches risk, orders, sizing, the
 * broker or the daily history), further updates are rejected-and-counted, and an operator
 * must clear the quarantine to accept the new level — a 2:1 split (−50%) must not be read
 * as a crash. Counted and logged, never silently dropped (error-path rule). The first live
 * update after a warm/stale load is exempt (the market legitimately moved while we were
 * down). Ordinary dividends (~0.5–2%) are BELOW any sane threshold and are not detected —
 * that needs a real corporate-action data source, tracked, not faked.
 *
 * <p><b>Multi-source freshness guard (ADR-0056):</b> when more than one source marks an instrument
 * (e.g. a real-time Finnhub WS with a delayed Yahoo fallback), an update whose PROVIDER timestamp is
 * older than the stored live mark's is skipped-and-counted, so a delayed source can never regress a
 * fresher mark backwards in time. A stale/warm or first mark is exempt, so a fallback still fills a
 * genuine gap — the property that keeps a single-source hiccup from blanking a position's exposure.
 */
public final class MarkCache {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MarkCache.class);

    /** Max single-update move in bps of the previous mark for one instrument; <=0 disables. */
    public interface JumpThresholds {
        int maxJumpBps(String instrumentId);

        JumpThresholds DISABLED = instrumentId -> 0;
    }

    /**
     * Durable store for the quarantine set so an operator's corporate-action freeze survives
     * a process restart (a safety control a restart could silently disarm is worse than none).
     * Transitions are rare (a jump trip or an operator clear), never per-tick, so a
     * synchronous write here is fine — this port is NEVER called from the per-tick accept path.
     */
    public interface QuarantineStore {
        void onQuarantine(String instrumentId, long lastGoodScaled, long suspectScaled);

        void onClear(String instrumentId);

        /** Persisted quarantines to restore at boot. */
        List<Persisted> load();

        record Persisted(String instrumentId, long lastGoodScaled, long suspectScaled) {
        }

        QuarantineStore NONE = new QuarantineStore() {
            @Override
            public void onQuarantine(String instrumentId, long lastGoodScaled, long suspectScaled) {
            }

            @Override
            public void onClear(String instrumentId) {
            }

            @Override
            public List<Persisted> load() {
                return List.of();
            }
        };
    }

    /** Mutable per-instrument holder. Fields volatile: written by core loop, read anywhere. */
    public static final class MarkHolder {
        private volatile long priceScaled;
        private volatile long providerTimestampMillis;
        private volatile long ingestTimestampMillis;
        private volatile String source;
        private volatile boolean stale;
        private volatile boolean quarantined;
        private volatile long suspectPriceScaled; // last rejected level (operator card shows it)

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

        public boolean quarantined() {
            return quarantined;
        }
    }

    /** Immutable boundary view (BigDecimal per invariant 1 at boundaries). */
    public record MarkSnapshot(String instrumentId, BigDecimal price, String source,
                               Instant providerTimestamp, Instant ingestTimestamp, boolean stale) {
    }

    private final ConcurrentHashMap<String, MarkHolder> marks = new ConcurrentHashMap<>();
    private final JumpThresholds thresholds;
    private final QuarantineStore quarantineStore;
    private final java.util.concurrent.atomic.LongAdder rejectedTicks = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder supersededTicks = new java.util.concurrent.atomic.LongAdder();

    public MarkCache() {
        this(JumpThresholds.DISABLED);
    }

    public MarkCache(JumpThresholds thresholds) {
        this(thresholds, QuarantineStore.NONE);
    }

    public MarkCache(JumpThresholds thresholds, QuarantineStore quarantineStore) {
        this.thresholds = thresholds;
        this.quarantineStore = quarantineStore != null ? quarantineStore : QuarantineStore.NONE;
    }

    /**
     * Restores persisted quarantines at boot (call after warm-load): each returns to the
     * quarantined state with its frozen last-good and rejected suspect level, so incoming
     * marks are rejected exactly as before the restart (the {@code quarantined} check runs
     * before the stale/jump logic, so a restored quarantine is NOT exempt like a stale load).
     */
    public void restoreQuarantines() {
        for (QuarantineStore.Persisted p : quarantineStore.load()) {
            MarkHolder holder = marks.computeIfAbsent(p.instrumentId(), k -> new MarkHolder());
            holder.priceScaled = p.lastGoodScaled();
            holder.suspectPriceScaled = p.suspectScaled();
            holder.quarantined = true;
            holder.stale = false; // a quarantine is a live freeze, not a warm-load
            log.warn("restored quarantine for {} (last good {}, suspect {}) — still frozen until "
                    + "an operator clears it", p.instrumentId(), p.lastGoodScaled(), p.suspectScaled());
        }
    }

    /** Hot path: live update from the core loop. Scaled-long arithmetic only. */
    public void update(String instrumentId, long priceScaled,
                       long providerTimestampMillis, long ingestTimestampMillis, String source) {
        MarkHolder holder = marks.computeIfAbsent(instrumentId, k -> new MarkHolder());
        if (holder.quarantined) {
            holder.suspectPriceScaled = priceScaled; // keep the freshest suspect level visible
            rejectedTicks.increment();
            return;
        }
        // Freshness guard (ADR-0056): with more than one source on an instrument (e.g. a real-time
        // Finnhub WS with a delayed Yahoo fallback), a laggard mark must never regress a fresher one
        // backwards in time. Reject an update whose PROVIDER timestamp is strictly older than the stored
        // live mark's — provider time is the market's honest clock (invariant 5), unlike ingest time which
        // only reflects our poll cadence. A stale/warm holder or a first mark (priceScaled==0) is exempt,
        // so a fallback still fills a genuine gap. Counted, never silently dropped (data-path rule).
        if (!holder.stale && holder.priceScaled > 0
                && providerTimestampMillis < holder.providerTimestampMillis) {
            supersededTicks.increment();
            return;
        }
        long prev = holder.priceScaled;
        if (!holder.stale && prev > 0) {
            int maxBps = thresholds.maxJumpBps(instrumentId);
            // |new − prev| / prev > maxBps/10^4, cross-multiplied (no division, no floats).
            if (maxBps > 0 && Math.abs(priceScaled - prev) * 10_000L > prev * (long) maxBps) {
                holder.quarantined = true;
                holder.suspectPriceScaled = priceScaled;
                rejectedTicks.increment();
                log.warn("QUARANTINE {}: mark jumped past {}bps (last good {}, suspect {}) — possible "
                                + "corporate action or bad print; trading data frozen until an operator "
                                + "clears it (POST /api/marks/{}/clear-quarantine)",
                        instrumentId, maxBps, prev, priceScaled, instrumentId);
                // Durable so the freeze survives a restart (this is the rare trip path, not per-tick).
                quarantineStore.onQuarantine(instrumentId, prev, priceScaled);
                return;
            }
        }
        holder.priceScaled = priceScaled;
        holder.providerTimestampMillis = providerTimestampMillis;
        holder.ingestTimestampMillis = ingestTimestampMillis;
        holder.source = source;
        holder.stale = false;
    }

    /** Operator accepts the new price level (a real split/reprice): the NEXT update becomes
     *  the new baseline unconditionally. @return true if the instrument was quarantined. */
    public boolean clearQuarantine(String instrumentId) {
        MarkHolder holder = marks.get(instrumentId);
        if (holder == null || !holder.quarantined) {
            return false;
        }
        holder.quarantined = false;
        holder.stale = true; // stale ⇒ the next live update is exempt from the jump guard
        quarantineStore.onClear(instrumentId); // drop the durable freeze too
        log.warn("quarantine CLEARED for {} — next mark accepted as the new baseline", instrumentId);
        return true;
    }

    /** One quarantined instrument: the frozen last-good mark and the rejected suspect level. */
    public record Quarantined(String instrumentId, BigDecimal lastGoodPrice, BigDecimal suspectPrice) {
    }

    /** Currently quarantined instruments (for the operator surface). Allocates; not per tick. */
    public List<Quarantined> quarantined() {
        List<Quarantined> out = new ArrayList<>();
        marks.forEach((id, h) -> {
            if (h.quarantined) {
                out.add(new Quarantined(id,
                        Decimals.fromScaledLong(h.priceScaled, Decimals.PRICE_SCALE),
                        Decimals.fromScaledLong(h.suspectPriceScaled, Decimals.PRICE_SCALE)));
            }
        });
        return out;
    }

    /** Total ticks rejected by the guard — the "never silently drop" counter. */
    public long rejectedTicks() {
        return rejectedTicks.sum();
    }

    /** Ticks skipped by the freshness guard (ADR-0056): a laggard/delayed source's mark that arrived
     *  after a fresher one — observed, not an error. Expected to be non-zero once sources overlap. */
    public long supersededTicks() {
        return supersededTicks.sum();
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
