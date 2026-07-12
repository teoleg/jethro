package io.jethro.app.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates one chat turn (ADR-0021): parse the question to an intent (SLM or keyword),
 * answer it deterministically, and audit the turn. Auditing is best-effort — a failed
 * write never fails the answer.
 */
public final class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatIntentParser parser;
    private final ChatResponder responder;
    private final ChatAuditStore audit;
    private final String modelId;

    public ChatService(ChatIntentParser parser, ChatResponder responder, ChatAuditStore audit, String modelId) {
        this.parser = parser;
        this.responder = responder;
        this.audit = audit;
        this.modelId = modelId;
    }

    public ChatResponse ask(String question) {
        long start = System.currentTimeMillis();
        ChatIntent intent = parser.parse(question);
        String answer = responder.answer(intent);
        ChatResponse response = new ChatResponse(
                question == null ? "" : question,
                intent.kind().name().toLowerCase(),
                intent.book(), intent.instrument(), answer, modelId);
        try {
            audit.record(response, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("chat audit write failed: {}", e.getMessage());
        }
        return response;
    }
}
