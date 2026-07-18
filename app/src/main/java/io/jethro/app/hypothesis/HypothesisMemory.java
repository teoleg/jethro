package io.jethro.app.hypothesis;

import io.jethro.trading.algo.hypothesis.Hypothesis;
import io.jethro.trading.algo.inference.EmbeddingClient;
import io.jethro.trading.algo.inference.SemanticMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Semantic memory over recent hypothesis triggers (ADR-0035): embeds each fired call's thesis and
 * flags a new call as a duplicate when it's cosine-near a recent one for the same instrument+
 * direction — catching the SAME story reworded across sources/cycles, which the deterministic
 * id/text guard ({@link HypothesisIdempotency}) cannot. It is an <b>added layer</b> on top of that
 * floor, not a replacement, and strictly best-effort: any embedding failure (model absent, Ollama
 * down) degrades silently to "not a duplicate" so the deterministic guard still governs and the
 * market path never depends on it (invariant 7). {@link #DISABLED} is the no-op when RAG is off.
 */
public final class HypothesisMemory {

    private static final Logger log = LoggerFactory.getLogger(HypothesisMemory.class);

    /** No-op memory (RAG disabled): never flags a duplicate, remembers nothing. */
    public static final HypothesisMemory DISABLED = new HypothesisMemory(null, null, 1.0);

    private final EmbeddingClient embeddings;          // null ⇒ disabled
    private final SemanticMemory<String> index;
    private final double dedupThreshold;
    private volatile boolean warnedUnavailable;

    public HypothesisMemory(EmbeddingClient embeddings, SemanticMemory<String> index, double dedupThreshold) {
        this.embeddings = embeddings;
        this.index = index;
        this.dedupThreshold = dedupThreshold;
    }

    public boolean enabled() {
        return embeddings != null && index != null;
    }

    /** True when this call is semantically near a recently fired one (same instrument+direction). */
    public boolean isSemanticDuplicate(Hypothesis h) {
        float[] v = embed(h);
        return v != null && index.hasSimilar(v, scope(h), dedupThreshold);
    }

    /** Records a fired call so later cycles can recognise a reworded repeat of it. */
    public void remember(Hypothesis h) {
        float[] v = embed(h);
        if (v != null) {
            index.add(v, h.hypothesisId(), scope(h));
        }
    }

    private float[] embed(Hypothesis h) {
        if (!enabled()) {
            return null;
        }
        try {
            return embeddings.embed(h.instrumentId() + " " + h.direction().name() + " " + h.thesis());
        } catch (RuntimeException e) {
            if (!warnedUnavailable) {
                warnedUnavailable = true;
                log.warn("RAG semantic de-dup unavailable ({}) — falling back to the deterministic "
                        + "guard only; pull the embedding model to enable it", e.getMessage());
            }
            return null; // best-effort: the deterministic guard remains the floor
        }
    }

    private static String scope(Hypothesis h) {
        return h.instrumentId() + "|" + h.direction().name();
    }
}
