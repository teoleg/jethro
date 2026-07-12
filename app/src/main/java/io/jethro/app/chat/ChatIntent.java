package io.jethro.app.chat;

/**
 * The structured understanding of an operator's question (ADR-0021). The SLM (or the
 * keyword fallback) produces this; deterministic handlers answer from it. The model never
 * writes the answer — only fills in {@code kind} and the optional slots.
 */
public record ChatIntent(Kind kind, String book, String instrument) {

    public enum Kind { PNL, EXPOSURE, POSITIONS, LIMITS, SIGNALS, HELP }

    public static ChatIntent of(Kind kind) {
        return new ChatIntent(kind, null, null);
    }
}
