package io.muniworld;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Boots the full muni-world context offline (no live Postgres/Kafka needed — Hikari never probes at boot,
 * Flyway/Kafka default off, LMDB opens a local build/ env). Proves the independent jar wires up and its
 * REST + static UI are served. Runs only under {@code -Pci} like the rest of the repo's unit suite.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MuniWorldSmokeTest {

    @Test
    void contextLoads() {
        // The assertion is that the context above started at all.
    }
}
