package io.jethro.app.ai;

import io.jethro.trading.algo.inference.InferenceRequest;
import io.jethro.trading.algo.inference.ModelInferenceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Warms the local SLM at startup (ADR-0016): the FIRST inference loads the model into memory,
 * which on a small box can take a minute or more and often times out as "ollama unreachable" —
 * so the first real hypothesis/commentary calls fail until it's resident. This fires one tiny
 * throwaway generation in the background (a few retries while Ollama comes up), absorbing that
 * cold-load cost once so the real calls start warm. Fully tolerant — never blocks startup, never
 * throws; a persistent failure just means Ollama isn't up yet and the normal loops will retry.
 */
public final class OllamaWarmup implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OllamaWarmup.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_BACKOFF_MILLIS = 15_000;

    private final ModelInferenceClient client;
    private volatile boolean running;
    private volatile Thread thread;

    public OllamaWarmup(ModelInferenceClient client) {
        this.client = client;
    }

    @Override
    public void start() {
        running = true;
        Thread t = new Thread(this::warm, "ollama-warmup");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    private void warm() {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS && running; attempt++) {
            try {
                long started = System.currentTimeMillis();
                client.complete(new InferenceRequest("", "ping", 1)); // 1 token — just load the model
                log.info("ollama warmup ok in {}ms — model resident, first real call won't cold-start",
                        System.currentTimeMillis() - started);
                return;
            } catch (RuntimeException e) {
                log.warn("ollama warmup attempt {}/{} failed ({}) — model may still be loading or Ollama "
                        + "not up yet; the hypothesis/commentary loops will retry", attempt, MAX_ATTEMPTS, e.getMessage());
                if (attempt < MAX_ATTEMPTS && sleep()) {
                    return; // interrupted (shutdown)
                }
            }
        }
    }

    /** @return true if interrupted (caller should stop). */
    private boolean sleep() {
        try {
            Thread.sleep(RETRY_BACKOFF_MILLIS);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    @Override
    public void stop() {
        running = false;
        var t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
