package io.jethro.app.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Periodically force-unloads the local chat model so llama-server's slow native growth is reset
 * (ADR-0016 ops hygiene). On a small box, {@code llama-server}'s RSS was observed to climb ~1.6GB
 * ABOVE the model weights under sustained polling and never shrink — a genuine native leak/fragmentation
 * that only a model UNLOAD reclaims. Short {@code keep_alive} lets a genuinely idle box unload on its own;
 * this recycler is the backstop for the case the narration loops keep the model warm continuously
 * (hypothesis cadence &lt; keep_alive), forcing a reset on a sawtooth (peak ≈ weights + one interval of
 * growth) instead of an unbounded climb into swap.
 *
 * <p>Evicts via {@code POST /api/generate} with {@code keep_alive: 0} (Ollama's documented unload). It
 * takes the shared {@link OllamaGate} first so it never collides with an in-flight inference on the
 * single-model box; if the gate is busy it skips and retries next cycle. The next real call reloads the
 * model (a few seconds on a 1.5B model — acceptable for advisory narration, never on the tick path,
 * invariant 7). Disabled when {@code jethro.ai.recycle-minutes} is 0.
 */
public final class ChatModelRecycler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ChatModelRecycler.class);

    private final ScheduledExecutorService scheduler;
    private final OllamaGate gate;
    private final HttpClient http;
    private final URI generateUri;
    private final String model;
    private final long recycleMinutes;
    private volatile ScheduledFuture<?> task;

    public ChatModelRecycler(ScheduledExecutorService scheduler, OllamaGate gate,
                             String baseUrl, String model, long recycleMinutes) {
        this.scheduler = scheduler;
        this.gate = gate;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.generateUri = URI.create(baseUrl.replaceAll("/$", "") + "/api/generate");
        this.model = model;
        this.recycleMinutes = recycleMinutes;
    }

    public void start() {
        if (recycleMinutes <= 0) {
            log.info("chat-model recycler disabled (jethro.ai.recycle-minutes=0)");
            return;
        }
        task = scheduler.scheduleWithFixedDelay(this::recycleOnce, recycleMinutes, recycleMinutes, TimeUnit.MINUTES);
        log.info("chat-model recycler started: force-unload {} every {}m", model, recycleMinutes);
    }

    private void recycleOnce() {
        if (!gate.tryAcquire()) {
            // A model call is in flight — skip; the model is busy (not leaking-while-idle) and we'll
            // catch the next window. Never block the gate waiting.
            return;
        }
        try {
            String body = "{\"model\":\"" + model + "\",\"keep_alive\":0}";
            HttpRequest req = HttpRequest.newBuilder(generateUri)
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                log.info("chat-model recycled: {} unloaded (llama-server memory reclaimed)", model);
            } else {
                log.warn("chat-model recycle got HTTP {} (skipped)", resp.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("chat-model recycle skipped: {}", e.toString());
        } finally {
            gate.release();
        }
    }

    @Override
    public void close() {
        var t = task;
        if (t != null) {
            t.cancel(false);
        }
    }
}
