package io.jethro.trading.marketdata.alpaca;

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
 * Real-time trade stream from Alpaca over WebSocket (ADR-0056): a genuinely-free, no-credit-card feed
 * for US equities on the IEX tier. Connects to {@code wss://stream.data.alpaca.markets/v2/iex}, does
 * Alpaca's two-step handshake — send {@code auth}, then {@code subscribe} once the server confirms
 * {@code authenticated} — and calls {@link TradeSink} for every trade. Reconnects with backoff on drop.
 * Dev/demo tier; the free IEX feed is real-time but only IEX-routed prints (thin on illiquid names) — a
 * delayed Yahoo fallback covers the gaps (ADR-0056 freshness guard picks the fresher mark). Never a
 * production/real-money feed.
 *
 * <p>Alpaca's v2 stream can emit msgpack; we request/expect JSON text frames. A BINARY frame therefore
 * means the server is on msgpack — logged loudly (once) rather than dropped silently, since no mark can
 * flow until that's resolved (the "never silently drop" data-path rule).
 */
public final class AlpacaWebSocketClient {

    private static final Logger log = LoggerFactory.getLogger(AlpacaWebSocketClient.class);
    private static final String URL = "wss://stream.data.alpaca.markets/v2/iex";

    /** Sink for normalised trades (provider symbol, exact price, source epoch-millis). */
    public interface TradeSink {
        void onTrade(String symbol, BigDecimal price, long epochMillis);
    }

    private final String keyId;
    private final String secret;
    private final List<String> symbols;
    private final TradeSink sink;
    private final HttpClient http = HttpClient.newHttpClient();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean warnedBinary = new AtomicBoolean(false);
    private volatile WebSocket ws;
    private volatile ScheduledExecutorService reconnector;

    public AlpacaWebSocketClient(String keyId, String secret, List<String> symbols, TradeSink sink) {
        this.keyId = keyId;
        this.secret = secret;
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
            Thread t = new Thread(r, "alpaca-reconnect");
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
                .buildAsync(URI.create(URL), new Listener())
                .whenComplete((socket, error) -> {
                    if (error != null) {
                        log.warn("alpaca connect failed: {} — retrying", error.toString());
                        scheduleReconnect();
                    } else {
                        ws = socket;
                        // Step 1: authenticate. Subscribe happens only after the server confirms auth.
                        socket.sendText("{\"action\":\"auth\",\"key\":\"" + keyId
                                + "\",\"secret\":\"" + secret + "\"}", true);
                        log.info("alpaca connected: authenticating ({} symbols queued)", symbols.size());
                    }
                });
    }

    private void subscribe(WebSocket socket) {
        StringBuilder trades = new StringBuilder();
        for (int i = 0; i < symbols.size(); i++) {
            if (i > 0) {
                trades.append(',');
            }
            trades.append('"').append(symbols.get(i)).append('"');
        }
        socket.sendText("{\"action\":\"subscribe\",\"trades\":[" + trades + "]}", true);
        connected.set(true);
        log.info("alpaca subscribed: {} symbols (real-time IEX trades)", symbols.size());
    }

    private void scheduleReconnect() {
        connected.set(false);
        var socket = ws;
        if (socket != null) {
            socket.abort(); // drop the dead connection's resources rather than stranding them
        }
        var r = reconnector;
        if (running.get() && r != null && !r.isShutdown()) {
            r.schedule(this::connect, 5, TimeUnit.SECONDS);
        }
    }

    /** Accumulates text frames (a message may fragment) and handles complete messages. */
    private final class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            webSocket.request(1);
            if (last) {
                String message = buffer.toString();
                buffer.setLength(0);
                handle(webSocket, message);
            }
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, java.nio.ByteBuffer data, boolean last) {
            webSocket.request(1);
            if (warnedBinary.compareAndSet(false, true)) {
                log.error("alpaca sent a BINARY (msgpack) frame — this adapter expects JSON text; no marks "
                        + "will flow until msgpack decoding is added. See ADR-0056.");
            }
            return null;
        }

        private void handle(WebSocket webSocket, String message) {
            if (message.contains("\"T\":\"error\"")) {
                log.warn("alpaca stream error: {}", message);
                return;
            }
            // Auth ack: {"T":"success","msg":"authenticated"} → now safe to subscribe.
            if (message.contains("\"msg\":\"authenticated\"")) {
                subscribe(webSocket);
                return;
            }
            try {
                long now = System.currentTimeMillis();
                for (AlpacaTradeParser.Trade t : AlpacaTradeParser.parse(message, now)) {
                    sink.onTrade(t.symbol(), t.price(), t.epochMillis());
                }
            } catch (RuntimeException e) {
                log.debug("alpaca message parse failed: {}", e.toString());
            }
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.info("alpaca socket closed ({}): {}", statusCode, reason);
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("alpaca socket error: {}", error.toString());
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
