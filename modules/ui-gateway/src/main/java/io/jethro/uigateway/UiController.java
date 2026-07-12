package io.jethro.uigateway;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/** REST + SSE surface for the UI (registered as a bean by the app assembly). */
@RestController
public class UiController {

    private final MarkState markState;
    private final MarkHistory markHistory;
    private final AttentionFeed feed;
    private final SseBroadcaster sse;

    public UiController(MarkState markState, MarkHistory markHistory, AttentionFeed feed, SseBroadcaster sse) {
        this.markState = markState;
        this.markHistory = markHistory;
        this.feed = feed;
        this.sse = sse;
    }

    @GetMapping("/api/marks")
    public List<MarkState.MarkDto> marks() {
        return markState.snapshot(System.currentTimeMillis());
    }

    @GetMapping("/api/attention")
    public List<AttentionFeed.AttentionItem> attention() {
        return feed.snapshot();
    }

    /** Recent price history for the interactive chart: last {@code minutes} (default 120). */
    @GetMapping("/api/history/{instrumentId}")
    public List<MarkHistory.Point> history(@PathVariable("instrumentId") String instrumentId,
                                           @RequestParam(name = "minutes", defaultValue = "120") long minutes) {
        long since = System.currentTimeMillis() - Math.max(1, minutes) * 60_000L;
        return markHistory.since(instrumentId, since);
    }

    @GetMapping("/api/stream")
    public SseEmitter stream(jakarta.servlet.http.HttpServletRequest request) {
        // When a browser drops mid-stream (tab refresh/navigation), Tomcat re-dispatches
        // the errored request back through the servlet. Handing out a fresh emitter then
        // makes Spring try startAsync on an ERROR-state response ("Cannot start async").
        // Only register on the original REQUEST dispatch; re-dispatches get nothing.
        if (request.getDispatcherType() != jakarta.servlet.DispatcherType.REQUEST) {
            return null;
        }
        return sse.register();
    }
}
