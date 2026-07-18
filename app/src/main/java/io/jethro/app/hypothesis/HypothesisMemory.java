package io.jethro.app.hypothesis;

import io.jethro.trading.algo.hypothesis.Hypothesis;
import io.jethro.trading.algo.hypothesis.NarrativeItem;
import io.jethro.trading.algo.inference.EmbeddingClient;
import io.jethro.trading.algo.inference.SemanticMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Retrieval-augmented memory for the hypothesis layer (ADR-0035). Two jobs, both advisory (a
 * similarity is context, never a number into risk — invariant 7), both best-effort (any embedding
 * failure degrades silently so the deterministic guard stands and the market path never depends on
 * it):
 *
 * <ol>
 *   <li><b>Semantic de-dup</b> — embeds each fired call's thesis and flags a reworded same-story
 *       repeat (same instrument+direction), catching what the deterministic id/text guard cannot.</li>
 *   <li><b>Outcome memory</b> — embeds each <em>scored</em> call and, at generation time, retrieves
 *       the nearest past outcomes for the instruments in today's news (queried by headline), so the
 *       model reasons with its own track record ("last time similar news drove this call it lost").</li>
 * </ol>
 *
 * <p><b>Durability without pgvector:</b> the vectors are derived data — the durable source is the
 * {@code hypothesis_record} table. The outcome index is rebuilt by re-embedding persisted scored
 * records at boot ({@link #rememberOutcome}), so it survives a restart with no vector store. A
 * persistent vector DB would only save the re-embedding cost at large scale (ADR-0035 follow-up).
 */
public final class HypothesisMemory {

    private static final Logger log = LoggerFactory.getLogger(HypothesisMemory.class);
    private static final int MAX_PAST_OUTCOMES = 6; // cap the memory injected into the prompt
    private static final int THESIS_DIGEST_CHARS = 90;

    /** No-op memory (RAG disabled): never flags a duplicate, remembers nothing, recalls nothing. */
    public static final HypothesisMemory DISABLED = new HypothesisMemory(null, null, null, 1.0, 1.0);

    private final EmbeddingClient embeddings;        // null ⇒ disabled
    private final SemanticMemory<String> dedupIndex; // fired theses, scope = instrument|direction
    private final SemanticMemory<String> outcomeIndex; // scored calls, scope = instrument
    private final double dedupThreshold;
    private final double recallThreshold;
    private volatile boolean warnedUnavailable;

    // Observability (ADR-0035): counters so the ops view can show whether RAG is actually working —
    // how many chunks are indexed, the live embedding dimension, and the retrieval hit/miss rate.
    private final java.util.concurrent.atomic.AtomicLong dedupChecks = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong dedupHits = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong recallQueries = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong recallHits = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong embedCalls = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong embedFailures = new java.util.concurrent.atomic.AtomicLong();
    private volatile int lastEmbeddingDim = -1; // dimension of the last successful embedding, -1 = none yet
    // Back off when the embedder is failing (e.g. the model isn't pulled): after a run of failures,
    // stop calling Ollama for a cooldown so RAG can't hammer a sick/absent model every cycle.
    private static final int EMBED_FAIL_THRESHOLD = 4;
    private static final long EMBED_COOLDOWN_MILLIS = 60_000;
    private final java.util.concurrent.atomic.AtomicInteger consecutiveEmbedFailures =
            new java.util.concurrent.atomic.AtomicInteger();
    private volatile long embedCooldownUntilMillis = 0;

    /** RAG health for the ops view: is it on, the model + live embedding dimension, indexed chunk
     *  counts, and the retrieval hit/miss counters (a hit = retrieval found a similar chunk). */
    public record RagStats(boolean enabled, String modelId, int embeddingDim,
                           int dedupChunks, int outcomeChunks,
                           long dedupChecks, long dedupHits,
                           long recallQueries, long recallHits,
                           long embedCalls, long embedFailures,
                           double dedupThreshold, double recallThreshold) {
    }

    public RagStats stats() {
        String model = null;
        if (embeddings != null) {
            try {
                model = embeddings.modelId();
            } catch (RuntimeException ignore) {
                // best-effort — the ops view shows model "unknown" rather than failing
            }
        }
        return new RagStats(enabled(), model, lastEmbeddingDim,
                dedupIndex != null ? dedupIndex.size() : 0,
                outcomeIndex != null ? outcomeIndex.size() : 0,
                dedupChecks.get(), dedupHits.get(), recallQueries.get(), recallHits.get(),
                embedCalls.get(), embedFailures.get(), dedupThreshold, recallThreshold);
    }

    public HypothesisMemory(EmbeddingClient embeddings, SemanticMemory<String> dedupIndex,
                            SemanticMemory<String> outcomeIndex, double dedupThreshold, double recallThreshold) {
        this.embeddings = embeddings;
        this.dedupIndex = dedupIndex;
        this.outcomeIndex = outcomeIndex;
        this.dedupThreshold = dedupThreshold;
        this.recallThreshold = recallThreshold;
    }

    public boolean enabled() {
        return embeddings != null && dedupIndex != null && outcomeIndex != null;
    }

    // ---- semantic de-dup ----

    /** True when this call is semantically near a recently fired one (same instrument+direction). */
    public boolean isSemanticDuplicate(Hypothesis h) {
        float[] v = embed(h.instrumentId() + " " + h.direction().name() + " " + h.thesis());
        if (v == null) {
            return false;
        }
        dedupChecks.incrementAndGet();
        boolean hit = dedupIndex.hasSimilar(v, dedupScope(h), dedupThreshold);
        if (hit) {
            dedupHits.incrementAndGet();
        }
        return hit;
    }

    /** Records a fired call so later cycles can recognise a reworded repeat of it. */
    public void remember(Hypothesis h) {
        float[] v = embed(h.instrumentId() + " " + h.direction().name() + " " + h.thesis());
        if (v != null) {
            dedupIndex.add(v, h.hypothesisId(), dedupScope(h));
        }
    }

    // ---- outcome memory ----

    /** Records a SCORED call's outcome (embedded on its thesis, scoped by instrument) so future
     *  prompts can recall it. Also the boot-time rebuild path: re-embed persisted records. */
    public void rememberOutcome(String instrumentId, String direction, String thesis,
                                String outcome, String pnl) {
        if (!enabled() || instrumentId == null || thesis == null || outcome == null) {
            return;
        }
        float[] v = embed(thesis);
        if (v != null) {
            outcomeIndex.add(v, digest(instrumentId, direction, thesis, outcome, pnl), instrumentId);
        }
    }

    /** Past outcomes semantically nearest to today's news, per instrument the news concerns —
     *  the model's own track record on similar setups. Empty when disabled/cold. */
    public List<String> recallSimilar(List<NarrativeItem> narrative) {
        if (!enabled() || narrative == null || narrative.isEmpty() || outcomeIndex.size() == 0) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>(); // dedupe identical digests, keep order
        for (NarrativeItem item : narrative) {
            if (item.instrumentId() == null || item.headline() == null) {
                continue; // macro/unattributed news has no instrument scope to query
            }
            float[] v = embed(item.headline());
            if (v == null) {
                continue;
            }
            recallQueries.incrementAndGet();
            List<SemanticMemory.Match<String>> matches =
                    outcomeIndex.nearest(v, item.instrumentId(), 2, recallThreshold);
            if (!matches.isEmpty()) {
                recallHits.incrementAndGet();
            }
            for (SemanticMemory.Match<String> m : matches) {
                out.add(m.payload());
                if (out.size() >= MAX_PAST_OUTCOMES) {
                    return new ArrayList<>(out);
                }
            }
        }
        return new ArrayList<>(out);
    }

    // ---- helpers ----

    private float[] embed(String text) {
        if (!enabled()) {
            return null;
        }
        if (System.currentTimeMillis() < embedCooldownUntilMillis) {
            return null; // backing off after repeated failures — don't hammer a sick/absent model
        }
        embedCalls.incrementAndGet();
        try {
            float[] v = embeddings.embed(text);
            if (v != null && v.length > 0) {
                lastEmbeddingDim = v.length;
            }
            consecutiveEmbedFailures.set(0);
            return v;
        } catch (RuntimeException e) {
            embedFailures.incrementAndGet();
            if (consecutiveEmbedFailures.incrementAndGet() >= EMBED_FAIL_THRESHOLD) {
                embedCooldownUntilMillis = System.currentTimeMillis() + EMBED_COOLDOWN_MILLIS;
            }
            if (!warnedUnavailable) {
                warnedUnavailable = true;
                log.warn("RAG retrieval unavailable ({}) — falling back to the deterministic guard "
                        + "and no memory; pull the embedding model to enable it", e.getMessage());
            }
            return null; // best-effort
        }
    }

    private static String dedupScope(Hypothesis h) {
        return h.instrumentId() + "|" + h.direction().name();
    }

    private static String digest(String instrumentId, String direction, String thesis,
                                 String outcome, String pnl) {
        String shortThesis = thesis.length() > THESIS_DIGEST_CHARS
                ? thesis.substring(0, THESIS_DIGEST_CHARS) + "…" : thesis;
        String dir = direction == null ? "" : direction + " ";
        String pnlPart = pnl == null || pnl.isBlank() ? "" : " (" + pnl + ")";
        return dir + instrumentId + " \"" + shortThesis + "\" → " + outcome + pnlPart;
    }
}
