package io.jethro.trading.marketdata.yahoo;

import io.jethro.domain.Decimals;
import io.jethro.trading.marketdata.MarketDataListener;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The adapter maps provider symbols to instrumentIds (invariant 2) and never leaks Yahoo symbols. */
class YahooMarketDataAdapterTest {

    private record Tick(String instrumentId, long priceScaled, long providerMillis) {
    }

    @Test
    void publishesInternalInstrumentIdsNotProviderSymbols() throws InterruptedException {
        // Fake source keyed by Yahoo symbol; the adapter must translate to instrumentIds.
        QuoteSource source = symbol -> switch (symbol) {
            case "ES=F" -> Optional.of(new QuoteSource.Quote(new BigDecimal("5450.25"), 1_700_000_000L));
            case "EURUSD=X" -> Optional.of(new QuoteSource.Quote(new BigDecimal("1.0855"), 1_700_000_000L));
            default -> Optional.empty();
        };
        var adapter = new YahooMarketDataAdapter(source,
                Map.of("ES", "ES=F", "EURUSD", "EURUSD=X"), null, 50);

        List<Tick> ticks = new CopyOnWriteArrayList<>();
        MarketDataListener listener = (id, price, qty, provTs, ingTs) -> ticks.add(new Tick(id, price, provTs));
        adapter.start(listener);
        // Give the poll thread a couple of cycles.
        long deadline = System.currentTimeMillis() + 2_000;
        while (ticks.size() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        adapter.stop();

        Map<String, Long> latest = new ConcurrentHashMap<>();
        for (Tick t : ticks) {
            assertTrue(t.instrumentId().equals("ES") || t.instrumentId().equals("EURUSD"),
                    "must publish instrumentIds, got " + t.instrumentId());
            latest.put(t.instrumentId(), t.priceScaled());
        }
        assertEquals(Decimals.toScaledLong(new BigDecimal("5450.25"), Decimals.PRICE_SCALE),
                latest.get("ES"));
        assertEquals(Decimals.toScaledLong(new BigDecimal("1.0855"), Decimals.PRICE_SCALE),
                latest.get("EURUSD"));
        assertEquals("yahoo", adapter.name());
    }

    @Test
    void failedFetchesAreCountedNotFabricated() throws InterruptedException {
        QuoteSource down = symbol -> Optional.empty(); // every fetch fails
        var adapter = new YahooMarketDataAdapter(down, Map.of("AAPL", "AAPL"), null, 50);
        List<Tick> ticks = new CopyOnWriteArrayList<>();
        adapter.start((id, price, qty, p, i) -> ticks.add(new Tick(id, price, p)));
        Thread.sleep(300);
        adapter.stop();
        assertTrue(ticks.isEmpty(), "no marks should be fabricated when the feed is down");
        assertTrue(adapter.drops() > 0, "failed fetches must be counted");
    }
}
