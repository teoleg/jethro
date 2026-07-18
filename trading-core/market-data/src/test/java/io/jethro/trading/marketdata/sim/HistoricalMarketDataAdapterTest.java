package io.jethro.trading.marketdata.sim;

import io.jethro.trading.marketdata.MarketDataListener;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The historical adapter emits trades (with real per-tick volume) and quotes off the bootstrap. */
class HistoricalMarketDataAdapterTest {

    @Test
    void emitsTradesWithRealVolumeAndQuotes() throws InterruptedException {
        List<String> ids = List.of("AAPL", "ES");
        var snapshot = HistoricalSnapshot.synthetic(1, ids,
                new double[]{190, 5450}, new double[]{0.28, 0.16},
                new long[]{50_000_000L, 1_500_000L}, 300);
        var adapter = new HistoricalMarketDataAdapter(42, snapshot, ids,
                new long[]{190_000_000L, 5_450_000_000L}, null, // no curve
                1_000_000L /* 1ms tick */, 120, 5.0,
                id -> new Quotes.QuoteSpec(500, false));

        var latch = new CountDownLatch(20);
        Map<String, Long> lastQty = new ConcurrentHashMap<>();
        AtomicInteger quotes = new AtomicInteger();
        adapter.start(new MarketDataListener() {
            @Override
            public void onTrade(String id, long price, long qty, long p, long in) {
                lastQty.put(id, qty);
                latch.countDown();
            }

            @Override
            public void onQuote(String id, long bid, long ask, long p, long in) {
                quotes.incrementAndGet();
            }
        });
        boolean got = latch.await(3, TimeUnit.SECONDS);
        adapter.stop();

        assertTrue(got, "the adapter should emit trades on its feed thread");
        assertTrue(lastQty.getOrDefault("AAPL", 0L) > 1_000_000L,
                "AAPL prints must carry real per-tick volume, not a unit default");
        assertTrue(quotes.get() > 0, "quotes should be synthesized around the mid");
    }
}
