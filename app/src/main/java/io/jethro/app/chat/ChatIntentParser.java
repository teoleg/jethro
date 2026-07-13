package io.jethro.app.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jethro.trading.algo.inference.InferenceRequest;
import io.jethro.trading.algo.inference.ModelInferenceClient;

import java.util.Set;
import java.util.function.Supplier;

/**
 * Parses an operator question into a {@link ChatIntent} (ADR-0021: the SLM's only job is to
 * understand the question). Tries the model first — asked to emit strict JSON
 * {@code {intent, book, instrument}} — and falls back to deterministic keyword matching if
 * the model is absent, errors, or returns something unusable. Slots are validated against
 * the known book/instrument sets, so a hallucinated symbol is dropped, never answered.
 */
public final class ChatIntentParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
            You classify an operator's question about a trading platform. Output ONLY a JSON \
            object, nothing else:
              {"intent": <one of: pnl, exposure, positions, limits, signals, help>,
               "book": <a book id or null>,
               "instrument": <an instrument id or null>}
            Rules: pick the single best intent; use "help" if unclear. Only use a book or \
            instrument id that appears in the question. Do not invent ids. Do not add text.""";

    private final ModelInferenceClient model; // nullable — keyword-only when absent
    private final Supplier<Set<String>> books;
    private final Supplier<Set<String>> instruments;

    public ChatIntentParser(ModelInferenceClient model,
                            Supplier<Set<String>> books, Supplier<Set<String>> instruments) {
        this.model = model;
        this.books = books;
        this.instruments = instruments;
    }

    public ChatIntent parse(String question) {
        if (question == null || question.isBlank()) {
            return ChatIntent.of(ChatIntent.Kind.HELP);
        }
        if (model != null) {
            try {
                ChatIntent viaModel = parseWithModel(question);
                if (viaModel != null) {
                    return viaModel;
                }
            } catch (RuntimeException ignored) {
                // fall through to keyword parsing
            }
        }
        return parseByKeyword(question);
    }

    private ChatIntent parseWithModel(String question) {
        String text = model.complete(new InferenceRequest(SYSTEM_PROMPT, question, 80)).text();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        JsonNode node;
        try {
            node = JSON.readTree(text.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
        ChatIntent.Kind kind = kindOf(node.path("intent").asText(""));
        if (kind == null) {
            return null;
        }
        return new ChatIntent(kind,
                validate(node.path("book").asText(null), books.get()),
                validate(node.path("instrument").asText(null), instruments.get()));
    }

    private ChatIntent parseByKeyword(String question) {
        String q = question.toLowerCase();
        ChatIntent.Kind kind;
        if (contains(q, "pnl", "p&l", "p & l", "profit", "loss", "made", "lost")) {
            kind = ChatIntent.Kind.PNL;
        } else if (contains(q, "exposure", "risk", "var", "notional")) {
            kind = ChatIntent.Kind.EXPOSURE;
        } else if (contains(q, "position", "holding", "long", "short")) {
            kind = ChatIntent.Kind.POSITIONS;
        } else if (contains(q, "limit", "breach", "cap", "headroom")) {
            kind = ChatIntent.Kind.LIMITS;
        } else if (contains(q, "signal", "strategy", "trading", "trades")) {
            kind = ChatIntent.Kind.SIGNALS;
        } else {
            kind = ChatIntent.Kind.HELP;
        }
        return new ChatIntent(kind, matchToken(question, books.get()), matchToken(question, instruments.get()));
    }

    private static ChatIntent.Kind kindOf(String raw) {
        try {
            return ChatIntent.Kind.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Returns the value only if it's a known id (case-insensitive), else null. */
    private static String validate(String value, Set<String> known) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) {
            return null;
        }
        for (String k : known) {
            if (k.equalsIgnoreCase(value.trim())) {
                return k;
            }
        }
        return null;
    }

    /** Finds the first known id that appears as a whole token in the question. */
    private static String matchToken(String question, Set<String> known) {
        for (String token : question.split("[^A-Za-z0-9]+")) {
            for (String k : known) {
                if (k.equalsIgnoreCase(token)) {
                    return k;
                }
            }
        }
        return null;
    }

    private static boolean contains(String q, String... needles) {
        for (String n : needles) {
            if (q.contains(n)) {
                return true;
            }
        }
        return false;
    }
}
