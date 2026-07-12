package io.jethro.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

@SpringBootTest
class JethroApplicationTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void lmdbInTempDir(DynamicPropertyRegistry registry) {
        registry.add("jethro.trading.lmdb-path", () -> tempDir.resolve("lmdb").toString());
    }

    @Test
    void contextLoads() {
        // The assembled application context must always start (and stop cleanly).
    }
}
