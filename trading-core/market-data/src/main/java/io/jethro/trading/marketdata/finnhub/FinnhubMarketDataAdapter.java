package io.jethro.trading.marketdata.finnhub;

import io.jethro.domain.Decimals;
import io.jethro.trading.marketdata.FeedStatus;
import io.jethro.trading.marketdata.MarketDataAdapter;
import io.jethro.trading.marketdata.MarketDataListener;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Finnhub real-time market-data adapter (ADR-0024): a free, push (WebSocket) feed for the
 * equities Finnhub covers, composed with a {@code background} sim adapter that supplies
 * everything Finnhub's free tier doesn't (index/FX futures, and the SOFR curve/swaps). Finnhub
 * symbols never cross the gateway (invariant 2) — they map to {@code instrumentId} here; provider
 * (trade) + ingest timestamps are both carried (invariant 5). Real-time, so effectively no data
 * delay. Dev/demo only — never a production/real-money feed.
 */
public final class FinnhubMarketDataAdapter implements MarketDataAdapter {

    public static final String NAME = "finnhub";

    private final String token;
    private final List<String> symbols;
    private final Map<String, String> symbolToInstrument; // Finnhub symbol → instrumentId
    private final MarketDataAdapter background;           // sim feed for everything Finnhub doesn't cover
    private final AtomicLong lastUpdateMillis = new AtomicLong(0);
    private final AtomicLong lastProviderEpoch = new AtomicLong(0);
    private volatile FinnhubWebSocketClient client;

    /** @param instrumentToSymbol instrumentId → Finnhub symbol for the covered (equity) names. */
    public FinnhubMarketDataAdapter(String token, Map<String, String> instrumentToSymbol,
                                    MarketDataAdapter background) {
        if (instrumentToSymbol.isEmpty()) {
            throw new IllegalArgumentException("at least one instrument→symbol mapping required");
        }
        this.token = token;
        this.background = background;
        this.symbolToInstrument = new LinkedHashMap<>();
        instrumentToSymbol.forEach((instrument, symbol) -> symbolToInstrument.put(symbol, instrument));
        this.symbols = List.copyOf(symbolToInstrument.keySet());
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void start(MarketDataListener listener) {
        background.start(listener); // sim: index/FX futures + SOFR curve/swaps
        client = new FinnhubWebSocketClient(token, symbols, (symbol, price, epochMillis) -> {
            String instrumentId = symbolToInstrument.get(symbol);
            if (instrumentId == null) {
                return; // not a mapped symbol — ignore (never leak provider symbology)
            }
            long now = System.currentTimeMillis();
            listener.onTrade(instrumentId, Decimals.toScaledLong(price, Decimals.PRICE_SCALE),
                    Decimals.toScaledLong(BigDecimal.ONE, Decimals.QTY_SCALE), epochMillis, now);
            lastUpdateMillis.set(now);
            lastProviderEpoch.set(epochMillis / 1000);
        });
        client.start();
    }

    @Override
    public FeedStatus status() {
        var c = client;
        boolean connected = c != null && c.connected();
        long delaySeconds = lastProviderEpoch.get() == 0 ? 0
                : Math.max(0, System.currentTimeMillis() / 1000 - lastProviderEpoch.get());
        return new FeedStatus(NAME, connected, lastUpdateMillis.get(), delaySeconds);
    }

    /** Both feeds this composite drives: Finnhub (real-time equities) + the background (Yahoo/sim). */
    @Override
    public java.util.List<FeedStatus> statuses() {
        java.util.List<FeedStatus> all = new java.util.ArrayList<>();
        all.add(status());
        all.addAll(background.statuses());
        return all;
    }

    @Override
    public void stop() {
        var c = client;
        if (c != null) {
            c.stop();
        }
        background.stop();
    }
}
