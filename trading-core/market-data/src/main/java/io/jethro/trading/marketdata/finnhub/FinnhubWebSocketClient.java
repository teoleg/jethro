package io.jethro.trading.marketdata.finnhub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Real-time trade stream from Finnhub over WebSocket (ADR-0024): push, not polling — so no
 * rate-limit-on-polling and sub-second updates, unlike the Yahoo poll (ADR-0023). Connects to
 * {@code wss://ws.finnhub.io?token=…}, subscribes the symbols, and calls {@link TradeSink} for
 * every trade. Reconnects with backoff on drop. Dev/demo tier (free key, IEX-ish coverage);
 * still not a production/real-money feed.
 */
public final class FinnhubWebSocketClient {

    private static final Logger log = LoggerFactory.getLogger(FinnhubWebSocketClient.class);
    private static final String URL = "wss://ws.finnhub.io?token=";

    /** Sink for normalised trades (provider symbol, exact price, source epoch-millis). */
    public interface TradeSink {
        void onTrade(String symbol, BigDecimal price, long epochMillis);
    }

    private final String token;
    private final List<String> symbols;
    private final TradeSink sink;
    private final HttpClient http = HttpClient.newHttpClient();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private volatile WebSocket ws;
    private volatile ScheduledExecutorService reconnector;

    public FinnhubWebSocketClient(String token, List<String> symbols, TradeSink sink) {
        this.token = token;
        this.symbols = List.copyOf(symbols);
        this.sink = sink;
    }

    public boolean connected() {
        return connected.get();
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        reconnector = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "finnhub-reconnect");
            t.setDaemon(true);
            return t;
        });
        connect();
    }

    private void connect() {
        if (!running.get()) {
            return;
        }
        http.newWebSocketBuilder()
                .buildAsync(URI.create(URL + token), new Listener())
                .whenComplete((socket, error) -> {
                    if (error != null) {
                        log.warn("finnhub connect failed: {} — retrying", error.toString());
                        scheduleReconnect();
                    } else {
                        ws = socket;
                        connected.set(true);
                        for (String symbol : symbols) {
                            socket.sendText("{\"type\":\"subscribe\",\"symbol\":\"" + symbol + "\"}", true);
                        }
                        log.info("finnhub connected: subscribed {} symbols", symbols.size());
                    }
                });
    }

    private void scheduleReconnect() {
        connected.set(false);
        var r = reconnector;
        if (running.get() && r != null && !r.isShutdown()) {
            r.schedule(this::connect, 5, TimeUnit.SECONDS);
        }
    }

    /** Accumulates text frames (WebSocket may fragment a message) and handles complete messages. */
    private final class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            webSocket.request(1);
            if (last) {
                String message = buffer.toString();
                buffer.setLength(0);
                try {
                    for (FinnhubTradeParser.Trade t : FinnhubTradeParser.parse(message)) {
                        sink.onTrade(t.symbol(), t.price(), t.epochMillis());
                    }
                } catch (RuntimeException e) {
                    log.debug("finnhub message parse failed: {}", e.toString());
                }
            }
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.info("finnhub socket closed ({}): {}", statusCode, reason);
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("finnhub socket error: {}", error.toString());
            scheduleReconnect();
        }
    }

    public void stop() {
        running.set(false);
        connected.set(false);
        var socket = ws;
        if (socket != null) {
            try {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
            } catch (Exception ignored) {
                // best effort
            }
        }
        var r = reconnector;
        if (r != null) {
            r.shutdownNow();
        }
    }
}
