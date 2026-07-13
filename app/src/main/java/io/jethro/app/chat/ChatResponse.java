package io.jethro.app.chat;

/** A chat turn's result: the understood intent (echoed for transparency) and the answer. */
public record ChatResponse(String question, String intent, String book, String instrument,
                           String answer, String model) {
}
