package io.jethro.trading.algo.inference;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The in-process semantic index (ADR-0035): cosine near-duplicate detection, scoped lookup,
 *  top-k retrieval order, and bounded eviction. */
class SemanticMemoryTest {

    @Test
    void detectsNearDuplicatesButNotOrthogonalOnes() {
        var mem = new SemanticMemory<String>(100);
        mem.add(new float[]{1, 0, 0}, "dollar-strength", "EURUSD");
        // Near-parallel → high cosine → duplicate.
        assertTrue(mem.hasSimilar(new float[]{0.99f, 0.01f, 0.0f}, "EURUSD", 0.9));
        // Orthogonal → cosine ~0 → not a duplicate.
        assertFalse(mem.hasSimilar(new float[]{0, 1, 0}, "EURUSD", 0.9));
    }

    @Test
    void scopeIsolatesInstruments() {
        var mem = new SemanticMemory<String>(100);
        mem.add(new float[]{1, 0}, "aapl-beat", "AAPL");
        // Same vector, different scope → not a duplicate for ES.
        assertFalse(mem.hasSimilar(new float[]{1, 0}, "ES", 0.9));
        assertTrue(mem.hasSimilar(new float[]{1, 0}, "AAPL", 0.9));
        // Null scope searches across everything.
        assertTrue(mem.hasSimilar(new float[]{1, 0}, null, 0.9));
    }

    @Test
    void nearestReturnsMostSimilarFirst() {
        var mem = new SemanticMemory<String>(100);
        mem.add(new float[]{1, 0}, "far", null);
        mem.add(new float[]{0, 1}, "exact", null);
        List<SemanticMemory.Match<String>> hits = mem.nearest(new float[]{0.1f, 1f}, null, 2, 0.0);
        assertEquals("exact", hits.get(0).payload(), "the most cosine-similar payload ranks first");
        assertTrue(hits.get(0).similarity() > hits.get(1).similarity());
    }

    @Test
    void boundedEvictionKeepsNewest() {
        var mem = new SemanticMemory<Integer>(2);
        mem.add(new float[]{1, 0}, 1, null);
        mem.add(new float[]{1, 0}, 2, null);
        mem.add(new float[]{1, 0}, 3, null);
        assertEquals(2, mem.size(), "capacity is enforced");
        List<SemanticMemory.Match<Integer>> hits = mem.nearest(new float[]{1, 0}, null, 5, 0.9);
        assertTrue(hits.stream().noneMatch(m -> m.payload() == 1), "the oldest entry was evicted");
    }

    @Test
    void zeroVectorIsIgnoredNoNaN() {
        var mem = new SemanticMemory<String>(10);
        mem.add(new float[]{0, 0, 0}, "degenerate", null);
        assertEquals(0, mem.size(), "a zero vector has no direction and is not stored");
        assertFalse(mem.hasSimilar(new float[]{0, 0, 0}, null, 0.5));
    }
}
