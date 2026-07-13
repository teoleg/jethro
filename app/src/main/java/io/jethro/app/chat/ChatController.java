package io.jethro.app.chat;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Operational chat REST surface (ADR-0021). */
@RestController
public final class ChatController {

    public record ChatRequest(String question) {
    }

    private final ChatService service;

    public ChatController(ChatService service) {
        this.service = service;
    }

    @PostMapping("/api/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        return service.ask(request.question());
    }
}
