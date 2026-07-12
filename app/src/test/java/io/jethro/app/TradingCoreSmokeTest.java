package io.jethro.app;

import io.jethro.app.trading.TradingCoreLifecycle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Step-2 definition of done: ticks flow through the assembled app. */
@SpringBootTest(properties = {"jethro.trading.sim-tick-interval-millis=1", "jethro.ai.enabled=false", "jethro.kafka.enabled=false"})
class TradingCoreSmokeTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void lmdbInTempDir(DynamicPropertyRegistry registry) {
        registry.add("jethro.trading.lmdb-path", () -> tempDir.resolve("lmdb").toString());
    }

    @Autowired
    TradingCoreLifecycle lifecycle;

    @Test
    void ticksFlowThroughTheAssembledApp() throws Exception {
        var runtime = lifecycle.runtime();
        assertNotNull(runtime, "trading-core must be running");

        long deadline = System.currentTimeMillis() + 5_000;
        while (runtime.stats().ticksIn() < 100) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("expected ticks to flow, got " + runtime.stats().ticksIn());
            }
            Thread.sleep(10);
        }

        assertTrue(runtime.markCache().size() >= 4, "all sim instruments should have marks");
        var mark = runtime.markCache().get("AAPL");
        assertNotNull(mark);
        assertTrue(mark.priceScaled() > 0);
    }
}
