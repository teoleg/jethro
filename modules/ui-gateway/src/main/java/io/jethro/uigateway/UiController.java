package io.jethro.uigateway;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/** REST + SSE surface for the UI (registered as a bean by the app assembly). */
@RestController
public class UiController {

    private final MarkState markState;
    private final AttentionFeed feed;
    private final SseBroadcaster sse;

    public UiController(MarkState markState, AttentionFeed feed, SseBroadcaster sse) {
        this.markState = markState;
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

    @GetMapping("/api/stream")
    public SseEmitter stream() {
        return sse.register();
    }
}
