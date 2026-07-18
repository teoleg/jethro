package io.jethro.trading.algo.inference;

/**
 * Text-embedding SPI (ADR-0035): one adapter per backend, first the local model via Ollama
 * ({@code nomic-embed-text}). Embeddings power retrieval — semantic news de-duplication and
 * past-outcome memory for the hypothesis layer. Same HARD LAW as {@link ModelInferenceClient}
 * (ADR-0016): a similarity score or a retrieved neighbour is advisory context only, never a
 * number parsed into a position, order, PnL or risk figure (invariant 7).
 */
public interface EmbeddingClient {

    /** Embedding model identity for the audit trail (e.g. {@code "nomic-embed-text"}). */
    String modelId();

    /** Embeds one text into a dense vector. Throws {@link InferenceException} on any failure —
     *  callers treat retrieval as best-effort and degrade to no-memory on error. */
    float[] embed(String text);
}
