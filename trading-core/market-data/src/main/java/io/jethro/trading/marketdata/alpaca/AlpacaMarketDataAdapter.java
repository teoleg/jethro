package io.jethro.trading.marketdata.alpaca;

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
 * Alpaca real-time market-data adapter (ADR-0056): a free (IEX-tier) WebSocket trade feed for the US
 * equities Alpaca covers, composed with a {@code background} adapter (Yahoo delayed poll / sim curve)
 * that supplies everything Alpaca's free tier doesn't — FX, index/rate futures, the SOFR curve — AND
 * the same equities as a DELAYED FALLBACK. The MarkCache freshness guard (ADR-0056) keeps the live
 * Alpaca mark winning over the delayed Yahoo one, so the two never "mix": Alpaca owns a name while it's
 * streaming, Yahoo only fills the gap when Alpaca is quiet. Alpaca symbols never cross the gateway
 * (invariant 2); provider (trade) + ingest timestamps are both carried (invariant 5). Dev/demo only.
 */
public final class AlpacaMarketDataAdapter implements MarketDataAdapter {

    public static final String NAME = "alpaca";

    private final String keyId;
    private final String secret;
    private final List<String> symbols;
    private final Map<String, String> symbolToInstrument; // Alpaca symbol → instrumentId
    private final MarketDataAdapter background;
    private final AtomicLong lastUpdateMillis = new AtomicLong(0);
    private final AtomicLong lastProviderEpoch = new AtomicLong(0);
    private volatile AlpacaWebSocketClient client;

    /** @param instrumentToSymbol instrumentId → Alpaca symbol for the covered (equity) names. */
    public AlpacaMarketDataAdapter(String keyId, String secret, Map<String, String> instrumentToSymbol,
                                   MarketDataAdapter background) {
        if (instrumentToSymbol.isEmpty()) {
            throw new IllegalArgumentException("at least one instrument→symbol mapping required");
        }
        this.keyId = keyId;
        this.secret = secret;
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
        background.start(listener); // Yahoo (delayed) for FX/futures + the equities as fallback; sim curve
        client = new AlpacaWebSocketClient(keyId, secret, symbols, (symbol, price, epochMillis) -> {
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

    /** Both feeds this composite drives: Alpaca (real-time equities) + the background (Yahoo/sim). */
    @Override
    public List<FeedStatus> statuses() {
        List<FeedStatus> all = new java.util.ArrayList<>();
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
