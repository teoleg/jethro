package io.jethro.uigateway;

import org.lmdbjava.CursorIterable;
import org.lmdbjava.Dbi;
import org.lmdbjava.DbiFlags;
import org.lmdbjava.Env;
import org.lmdbjava.KeyRange;
import org.lmdbjava.Txn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Durable {@link MarkHistory} on LMDB (ADR-0014: derived, rebuildable state on local disk —
 * losing the file costs a warm-up, never data). Backs the interactive chart so a long window
 * (1m–12h) survives a restart and reads fast, without replaying the whole {@code md.marks}
 * topic on boot.
 *
 * <p><b>Key layout</b> per point is {@code instrumentId + 0x00 + big-endian(timestampMillis)}.
 * Big-endian time makes LMDB's lexicographic byte order match chronological order, so
 * {@link #since} is a single ordered range scan from the {@code sinceMillis} lower bound.
 * Writing the same mark twice hits the same key (last-value upsert) — idempotent by shape
 * (invariant 8), so re-delivery on the broker or a boot replay never grows the store.
 *
 * <p><b>Value</b> is the price as a UTF-8 decimal string (invariant 1 — never a float).
 *
 * <p>Writes come from the single ui-gateway consumer thread (serialized); reads run on Tomcat
 * request threads under LMDB's MVCC read txns (concurrent with the writer). Each write prunes
 * the instrument's expired head, so the store stays bounded at ~retention.
 */
public final class LmdbMarkHistory implements MarkHistory, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LmdbMarkHistory.class);
    private static final String HISTORY_DB = "mark-history";
    private static final byte SEP = 0x00;

    /** Read-path cap: the chart never needs more than this many points for any window, so a
     *  12h series (~43k marks) is stride-downsampled to keep the payload and the browser light. */
    private static final int MAX_RETURNED_POINTS = 2_000;

    private final Env<ByteBuffer> env;
    private final Dbi<ByteBuffer> db;
    private final long retentionMillis;
    private final Set<String> seenInstruments = ConcurrentHashMap.newKeySet();

    private LmdbMarkHistory(Env<ByteBuffer> env, Dbi<ByteBuffer> db, long retentionMillis) {
        this.env = env;
        this.db = db;
        this.retentionMillis = retentionMillis;
    }

    /** Opens (or creates) the history store at {@code directory}, sized to {@code maxSizeBytes}. */
    public static LmdbMarkHistory open(Path directory, long maxSizeBytes, long retentionMillis) {
        File dir = directory.toFile();
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("cannot create mark-history LMDB directory " + directory);
        }
        Env<ByteBuffer> env = Env.create()
                .setMapSize(maxSizeBytes)
                .setMaxDbs(1)
                .open(dir);
        Dbi<ByteBuffer> db = env.openDbi(HISTORY_DB, DbiFlags.MDB_CREATE);
        log.info("mark-history LMDB at {} (retention {}min, map {}MB)",
                directory, retentionMillis / 60_000, maxSizeBytes / (1024 * 1024));
        return new LmdbMarkHistory(env, db, retentionMillis);
    }

    @Override
    public long retentionMillis() {
        return retentionMillis;
    }

    @Override
    public void record(String instrumentId, String price, long timestampMillis) {
        seenInstruments.add(instrumentId);
        byte[] id = instrumentId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer key = pointKey(id, timestampMillis);
        byte[] priceBytes = price.getBytes(StandardCharsets.UTF_8);
        ByteBuffer value = ByteBuffer.allocateDirect(priceBytes.length).put(priceBytes).flip();
        try (Txn<ByteBuffer> txn = env.txnWrite()) {
            db.put(txn, key, value);
            pruneExpired(txn, id, timestampMillis - retentionMillis);
            txn.commit();
        }
    }

    /** Deletes this instrument's points older than {@code cutoff}. At steady state this is 0–1
     *  keys per write; it only ever touches the expired head, never the live window. */
    private void pruneExpired(Txn<ByteBuffer> txn, byte[] id, long cutoff) {
        ByteBuffer from = pointKey(id, 0L);
        try (CursorIterable<ByteBuffer> it = db.iterate(txn, KeyRange.atLeast(from))) {
            for (CursorIterable.KeyVal<ByteBuffer> kv : it) {
                ByteBuffer k = kv.key();
                if (!keyBelongsTo(k, id)) {
                    break;
                }
                if (k.getLong(id.length + 1) >= cutoff) {
                    break; // keys are time-ordered — the first live one ends the sweep
                }
                db.delete(txn, k);
            }
        }
    }

    @Override
    public List<Point> since(String instrumentId, long sinceMillis) {
        byte[] id = instrumentId.getBytes(StandardCharsets.UTF_8);
        List<Point> out = new ArrayList<>();
        try (Txn<ByteBuffer> txn = env.txnRead()) {
            ByteBuffer from = pointKey(id, Math.max(0L, sinceMillis));
            try (CursorIterable<ByteBuffer> it = db.iterate(txn, KeyRange.atLeast(from))) {
                for (CursorIterable.KeyVal<ByteBuffer> kv : it) {
                    ByteBuffer k = kv.key();
                    if (!keyBelongsTo(k, id)) {
                        break;
                    }
                    long t = k.getLong(id.length + 1);
                    String price = StandardCharsets.UTF_8.decode(kv.val()).toString();
                    out.add(new Point(t, price));
                }
            }
        }
        return downsample(out);
    }

    /** Stride-downsamples to at most {@link #MAX_RETURNED_POINTS}, always keeping the last point
     *  so the chart's most-recent value is exact. A no-op below the cap. */
    private static List<Point> downsample(List<Point> points) {
        int n = points.size();
        if (n <= MAX_RETURNED_POINTS) {
            return points;
        }
        int stride = (n + MAX_RETURNED_POINTS - 1) / MAX_RETURNED_POINTS;
        List<Point> out = new ArrayList<>(MAX_RETURNED_POINTS + 1);
        for (int i = 0; i < n; i += stride) {
            out.add(points.get(i));
        }
        Point last = points.get(n - 1);
        if (!out.get(out.size() - 1).equals(last)) {
            out.add(last);
        }
        return out;
    }

    @Override
    public int instrumentCount() {
        return seenInstruments.size();
    }

    @Override
    public int pointCount(String instrumentId) {
        byte[] id = instrumentId.getBytes(StandardCharsets.UTF_8);
        int count = 0;
        try (Txn<ByteBuffer> txn = env.txnRead()) {
            ByteBuffer from = pointKey(id, 0L);
            try (CursorIterable<ByteBuffer> it = db.iterate(txn, KeyRange.atLeast(from))) {
                for (CursorIterable.KeyVal<ByteBuffer> kv : it) {
                    if (!keyBelongsTo(kv.key(), id)) {
                        break;
                    }
                    count++;
                }
            }
        }
        return count;
    }

    private static ByteBuffer pointKey(byte[] id, long timestampMillis) {
        ByteBuffer key = ByteBuffer.allocateDirect(id.length + 1 + Long.BYTES);
        key.put(id).put(SEP).putLong(timestampMillis).flip();
        return key;
    }

    /** True if {@code key} is {@code id + 0x00 + <8 time bytes>} — i.e. this instrument's row. */
    private static boolean keyBelongsTo(ByteBuffer key, byte[] id) {
        if (key.remaining() != id.length + 1 + Long.BYTES) {
            return false;
        }
        for (int i = 0; i < id.length; i++) {
            if (key.get(i) != id[i]) {
                return false;
            }
        }
        return key.get(id.length) == SEP;
    }

    @Override
    public void close() {
        env.close();
    }
}
