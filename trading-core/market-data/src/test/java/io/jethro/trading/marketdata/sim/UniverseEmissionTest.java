package io.jethro.trading.marketdata.sim;

import io.jethro.trading.marketdata.MarketDataListener;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof that the hedge-focused universe expansion actually reaches the tape: the
 * new factor names (JNJ/NVDA/JPM/AUDUSD) must all print when the adapter runs, not merely exist
 * in config. If they emit here, the UI — which renders whatever arrives on md.marks — shows them
 * after a rebuild + restart.
 */
class UniverseEmissionTest {

    private static final List<String> IDS = List.of("JNJ", "NVDA", "JPM", "AUDUSD");
    private static final long[] STARTS = {155_000_000L, 125_000_000L, 205_000_000L, 660_000L};

    private static FactorModelConfig cfg() {
        double[][] corr = {
                {1.00, 0.00, 0.00, -0.20},
                {0.00, 1.00, 0.00, 0.00},
                {0.00, 0.00, 1.00, 0.00},
                {-0.20, 0.00, 0.00, 1.00}};
        return new FactorModelConfig(0.18, 0.08, 5.0, 2.0, 6,
                List.of(
                        new FactorModelConfig.InstrumentSpec("JNJ", 0.16, 0.55, 0.0),
                        new FactorModelConfig.InstrumentSpec("NVDA", 0.45, 1.75, 0.0),
                        new FactorModelConfig.InstrumentSpec("JPM", 0.26, 1.15, 0.0),
                        new FactorModelConfig.InstrumentSpec("AUDUSD", 0.10, 0.30, -1.00)),
                List.of(new FactorModelConfig.RegimeSpec("CALM", 0.05, 0, 0, 1.0, corr)),
                new double[][]{{1.0}});
    }

    @Test
    void everyNewInstrumentPrintsOnTheTape() throws Exception {
        // curveSim = null: these are all factor instruments (none are curve-linked), so this
        // isolates the equities/FX expansion — exactly what "I don't see the new names" is about.
        var adapter = new CorrelatedMarketDataAdapter(7, cfg(), IDS, STARTS, null, 1, 300);
        Set<String> seen = ConcurrentHashMap.newKeySet();
        CountDownLatch allSeen = new CountDownLatch(1);
        MarketDataListener listener = (id, px, qty, providerTs, ingestTs) -> {
            seen.add(id);
            if (seen.size() == IDS.size()) {
                allSeen.countDown();
            }
        };
        Thread feed = new Thread(() -> adapter.start(listener), "test-feed");
        feed.setDaemon(true);
        feed.start();
        try {
            assertTrue(allSeen.await(5, TimeUnit.SECONDS),
                    "all new instruments must print on the tape; only saw " + seen);
        } finally {
            adapter.stop();
            feed.join(1_000);
        }
        assertTrue(seen.containsAll(IDS), "expected " + IDS + " but saw " + seen);
    }
}
