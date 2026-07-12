package io.jethro.trading.algo.inference;

/**
 * The model-inference SPI (ADR-0010): one adapter per backend. First adapter is the
 * local SLM via Ollama (ADR-0016); the frontier API adapter arrives with real trading
 * decisions. HARD LAW (ADR-0016): callers never parse a model output for a number
 * that feeds a position, order, PnL, or risk figure — deterministic code computes,
 * models narrate/triage/propose.
 */
public interface ModelInferenceClient {

    /** Model identity for the audit trail; specific ("qwen2.5:3b"), not generic ("ollama"). */
    String modelId();

    /** Blocking single completion. Throws {@link InferenceException} on transport/model failure. */
    InferenceResult complete(InferenceRequest request);
}
