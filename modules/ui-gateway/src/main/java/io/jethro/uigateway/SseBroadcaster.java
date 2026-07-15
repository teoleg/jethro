package io.jethro.uigateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CopyOnWriteArrayList;

/** Pushes named JSON events to connected browsers (SSE v0; WebSocket if bidirectional needs arrive). */
public final class SseBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(SseBroadcaster.class);
    private static final long EMITTER_TIMEOUT_MILLIS = 0L; // no server-side timeout

    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter register() {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        return emitter;
    }

    /**
     * Pushes an event to every connected client. <b>Synchronized</b>: many threads broadcast
     * concurrently (the mark loop, the strategy/hypothesis schedulers, the risk/scenario
     * monitors), and {@link SseEmitter#send} is NOT safe for concurrent calls on the same
     * emitter — two interleaved writes corrupt the one HTTP response and surface as a
     * "Broken pipe" IOException on a container thread. Serializing writes fixes that; broadcasts
     * are small and ~1Hz, so a single lock is ample.
     */
    public synchronized void broadcast(String eventName, Object payload) {
        SseEmitter.SseEventBuilder event = SseEmitter.event().name(eventName).data(payload);
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(event);
            } catch (Exception e) {
                // A disconnected browser (tab closed/refreshed) causes a broken-pipe
                // write — expected, not an error. Drop the client and complete the
                // emitter quietly so the async request is torn down cleanly.
                emitters.remove(emitter);
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // already closed
                }
                log.debug("dropped disconnected SSE client: {}", e.getMessage());
            }
        }
    }

    public int clientCount() {
        return emitters.size();
    }
}
