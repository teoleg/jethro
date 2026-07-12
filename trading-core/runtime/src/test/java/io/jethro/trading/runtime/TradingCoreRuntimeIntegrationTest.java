package io.jethro.trading.runtime;

import io.jethro.trading.marketdata.sim.SimMarketDataAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End to end inside the process: sim feed → ring buffer → core loop → mark cache → LMDB. */
class TradingCoreRuntimeIntegrationTest {

    private static final long TEN_MB = 10L * 1024 * 1024;

    @Test
    void ticksFlowFromSimFeedToMarkCache(@TempDir Path dir) throws Exception {
        var adapter = new SimMarketDataAdapter(42L, List.of("AAPL", "MSFT"), 100_000_000L, 1_000_000L);
        try (var store = openStore(dir);
             var runtime = new TradingCoreRuntime(adapter, 1024, store)) {
            runtime.start();

            awaitTrue(() -> runtime.stats().ticksIn() >= 100, 5_000);

            var aapl = runtime.markCache().get("AAPL");
            var msft = runtime.markCache().get("MSFT");
            assertNotNull(aapl);
            assertNotNull(msft);
            assertTrue(aapl.priceScaled() >= 10_000L, "mark must be a valid price");
            assertFalse(aapl.stale(), "live marks are not stale");
            assertEquals("sim", aapl.source());
            assertEquals(2, runtime.markCache().size());
        }
    }

    @Test
    void warmRestartLoadsStaleMarksFromLmdb(@TempDir Path dir) throws Exception {
        var adapter = new SimMarketDataAdapter(42L, List.of("AAPL"), 100_000_000L, 1_000_000L);
        try (var store = openStore(dir);
             var runtime = new TradingCoreRuntime(adapter, 1024, store)) {
            runtime.start();
            awaitTrue(() -> runtime.stats().ticksIn() >= 10, 5_000);
        } // close flushes marks to LMDB

        // "Restart": a fresh runtime over the same LMDB dir, feed not yet delivering
        var idleAdapter = new SimMarketDataAdapter(42L, List.of("AAPL"), 100_000_000L,
                java.util.concurrent.TimeUnit.HOURS.toNanos(1)); // effectively silent
        try (var store = openStore(dir);
             var runtime = new TradingCoreRuntime(idleAdapter, 1024, store)) {
            runtime.start();
            var warm = runtime.markCache().get("AAPL");
            assertNotNull(warm, "warm-restart must preload the last known mark");
            assertTrue(warm.priceScaled() >= 10_000L);
        }
    }

    private static LmdbStateStore openStore(Path dir) {
        return LmdbStateStore.open(dir, TEN_MB);
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("condition not met within " + timeoutMillis + "ms");
            }
            Thread.sleep(10);
        }
    }
}
