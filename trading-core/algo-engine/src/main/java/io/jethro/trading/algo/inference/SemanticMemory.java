package io.jethro.trading.algo.inference;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A small in-process semantic index (ADR-0035, first slice): recent embeddings with cosine
 * similarity, for two jobs — deciding whether new text is a semantic near-duplicate of something
 * seen (news de-dup beyond the id/text guard) and retrieving the nearest prior items (past-outcome
 * memory for the prompt). Bounded ring, newest-wins; no persistence — pgvector durability is the
 * follow-up. Advisory only: a similarity is context, never a number into risk (invariant 7).
 *
 * <p>Vectors are unit-normalized on insert, so cosine similarity is a dot product. Thread-safe via
 * coarse synchronization; the corpus here (recent news/theses) is tiny, so a linear scan is ample.
 */
public final class SemanticMemory<T> {

    /** A retrieval hit: the stored payload and its cosine similarity to the query. */
    public record Match<T>(T payload, double similarity) {
    }

    private record Entry<T>(float[] unit, T payload, String scope) {
    }

    private final int capacity;
    private final Deque<Entry<T>> entries = new ArrayDeque<>();

    public SemanticMemory(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.capacity = capacity;
    }

    /** Stores an embedding under an optional {@code scope} (e.g. instrument id; null = global). A
     *  zero/degenerate vector is ignored (nothing to compare against). */
    public synchronized void add(float[] embedding, T payload, String scope) {
        float[] unit = unit(embedding);
        if (unit == null) {
            return;
        }
        entries.addLast(new Entry<>(unit, payload, scope));
        while (entries.size() > capacity) {
            entries.removeFirst();
        }
    }

    /** True when any stored entry in {@code scope} is at least {@code threshold} cosine-similar. */
    public synchronized boolean hasSimilar(float[] embedding, String scope, double threshold) {
        float[] unit = unit(embedding);
        if (unit == null) {
            return false;
        }
        for (Entry<T> e : entries) {
            if (scopeMatches(e.scope, scope) && dot(unit, e.unit) >= threshold) {
                return true;
            }
        }
        return false;
    }

    /** The {@code k} most similar payloads at or above {@code minSimilarity}, most similar first.
     *  {@code scope} null retrieves across all scopes. */
    public synchronized List<Match<T>> nearest(float[] embedding, String scope, int k, double minSimilarity) {
        float[] unit = unit(embedding);
        List<Match<T>> hits = new ArrayList<>();
        if (unit == null) {
            return hits;
        }
        for (Entry<T> e : entries) {
            if (scope != null && !scopeMatches(e.scope, scope)) {
                continue;
            }
            double sim = dot(unit, e.unit);
            if (sim >= minSimilarity) {
                hits.add(new Match<>(e.payload, sim));
            }
        }
        hits.sort((a, b) -> Double.compare(b.similarity(), a.similarity()));
        return hits.size() > k ? new ArrayList<>(hits.subList(0, k)) : hits;
    }

    public synchronized int size() {
        return entries.size();
    }

    private static boolean scopeMatches(String entryScope, String queryScope) {
        return queryScope == null || queryScope.equals(entryScope);
    }

    /** Unit-normalizes a copy of the vector, or null if it's zero/degenerate. */
    private static float[] unit(float[] v) {
        if (v == null || v.length == 0) {
            return null;
        }
        double norm = 0;
        for (float x : v) {
            norm += (double) x * x;
        }
        norm = Math.sqrt(norm);
        if (!(norm > 0) || Double.isNaN(norm)) {
            return null;
        }
        float[] u = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            u[i] = (float) (v[i] / norm);
        }
        return u;
    }

    /** Dot product of two same-length unit vectors (= cosine); 0 if lengths differ. */
    private static double dot(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0;
        }
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += (double) a[i] * b[i];
        }
        return s;
    }
}
