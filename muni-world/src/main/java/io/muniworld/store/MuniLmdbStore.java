package io.muniworld.store;

import jakarta.annotation.PreDestroy;
import org.lmdbjava.Env;
import org.lmdbjava.EnvFlags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * muni-world's own embedded LMDB environment — memory-mapped, DERIVED data only (same discipline as the
 * jethro warm-restart cache: losing it costs a rebuild, never data). Separate env/path from jethro's so
 * the two subprojects never share a file. Requires the {@code --add-opens java.base/java.nio} JVM flag
 * (set on bootRun and in the test config).
 */
@Component
public final class MuniLmdbStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MuniLmdbStore.class);

    private final Env<ByteBuffer> env;

    public MuniLmdbStore(@Value("${muni.lmdb.path}") String path,
                         @Value("${muni.lmdb.map-size-mb}") long mapSizeMb) {
        File dir = new File(path);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("could not create LMDB dir: " + dir.getAbsolutePath());
        }
        // MDB_NOSYNC: don't fsync on commit — this store is DERIVED, search-index data only (ADR-0013);
        // a crash costs a reindex from Postgres, never data. Same posture as the jethro derived stores.
        this.env = Env.create()
                .setMapSize(mapSizeMb * 1024L * 1024L)
                .setMaxDbs(8)
                .open(dir, EnvFlags.MDB_NOSYNC);
        log.info("muni-world LMDB opened at {} (map {} MB)", dir.getAbsolutePath(), mapSizeMb);
    }

    /** The open environment — callers open named databases off it. */
    public Env<ByteBuffer> env() {
        return env;
    }

    @Override
    @PreDestroy
    public void close() {
        env.close();
    }
}
