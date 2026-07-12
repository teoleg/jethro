package io.jethro.trading.runtime;

import org.lmdbjava.Dbi;
import org.lmdbjava.DbiFlags;
import org.lmdbjava.Env;
import org.lmdbjava.PutFlags;
import org.lmdbjava.Txn;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Embedded local state (ADR-0014): the eventId dedupe table (invariant 6/8) and the
 * warm-restart mark cache. Derived data only — losing this file may cost restart
 * time, never data (invariant 9). Lives on a real local filesystem; S3-mounted
 * filesystems are rejected outright (no random-write/mmap semantics — see ADR-0014).
 */
public final class LmdbStateStore implements AutoCloseable {

    private static final String DEDUPE_DB = "dedupe";
    private static final String MARKS_DB = "marks";
    private static final int MAX_KEY_BYTES = 500;

    private final Env<ByteBuffer> env;
    private final Dbi<ByteBuffer> dedupe;
    private final Dbi<ByteBuffer> marks;

    private LmdbStateStore(Env<ByteBuffer> env, Dbi<ByteBuffer> dedupe, Dbi<ByteBuffer> marks) {
        this.env = env;
        this.dedupe = dedupe;
        this.marks = marks;
    }

    public static LmdbStateStore open(Path directory, long maxSizeBytes) {
        File dir = directory.toFile();
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("cannot create LMDB directory " + directory);
        }
        Env<ByteBuffer> env = Env.create()
                .setMapSize(maxSizeBytes)
                .setMaxDbs(2)
                .open(dir);
        Dbi<ByteBuffer> dedupe = env.openDbi(DEDUPE_DB, DbiFlags.MDB_CREATE);
        Dbi<ByteBuffer> marks = env.openDbi(MARKS_DB, DbiFlags.MDB_CREATE);
        return new LmdbStateStore(env, dedupe, marks);
    }

    /**
     * Records an eventId; returns true if it was new, false if seen before.
     * The idempotency primitive for at-least-once consumers (invariant 6/8).
     */
    public boolean markSeenIfNew(String eventId, long seenAtMillis) {
        ByteBuffer key = key(eventId);
        ByteBuffer value = ByteBuffer.allocateDirect(Long.BYTES).putLong(seenAtMillis).flip();
        try (Txn<ByteBuffer> txn = env.txnWrite()) {
            boolean inserted = dedupe.put(txn, key, value, PutFlags.MDB_NOOVERWRITE);
            txn.commit();
            return inserted;
        }
    }

    /** Persists the last mark for warm restart. Called at flush cadence, never per tick. */
    public void putMark(String instrumentId, long priceScaled, long providerTimestampMillis, String source) {
        ByteBuffer key = key(instrumentId);
        byte[] sourceBytes = source.getBytes(StandardCharsets.UTF_8);
        ByteBuffer value = ByteBuffer.allocateDirect(Long.BYTES * 2 + sourceBytes.length)
                .putLong(priceScaled)
                .putLong(providerTimestampMillis)
                .put(sourceBytes)
                .flip();
        try (Txn<ByteBuffer> txn = env.txnWrite()) {
            marks.put(txn, key, value);
            txn.commit();
        }
    }

    @FunctionalInterface
    public interface MarkVisitor {
        void visit(String instrumentId, long priceScaled, long providerTimestampMillis, String source);
    }

    /** Streams persisted marks (warm-restart load). */
    public void forEachMark(MarkVisitor visitor) {
        try (Txn<ByteBuffer> txn = env.txnRead()) {
            try (var cursor = marks.iterate(txn)) {
                for (var kv : cursor) {
                    String instrumentId = StandardCharsets.UTF_8.decode(kv.key()).toString();
                    ByteBuffer value = kv.val();
                    long price = value.getLong();
                    long providerTs = value.getLong();
                    String source = StandardCharsets.UTF_8.decode(value).toString();
                    visitor.visit(instrumentId, price, providerTs, source);
                }
            }
        }
    }

    private static ByteBuffer key(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_KEY_BYTES) {
            throw new IllegalArgumentException("key too long for LMDB: " + value);
        }
        return ByteBuffer.allocateDirect(bytes.length).put(bytes).flip();
    }

    @Override
    public void close() {
        env.close();
    }
}
