package io.jethro.trading.algo.inference;

/** Inference transport or model failure. Callers degrade gracefully — never blindly retry in a loop. */
public class InferenceException extends RuntimeException {

    public InferenceException(String message) {
        super(message);
    }

    public InferenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
