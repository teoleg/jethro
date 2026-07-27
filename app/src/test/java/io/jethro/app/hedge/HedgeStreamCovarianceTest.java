package io.jethro.app.hedge;

import io.jethro.app.fusion.StreamCovariance;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ADR-0095 hedge covariance basis: it reports nothing until its pairs are warm, it never
 * fabricates a cell for a pair it has not measured, and what it publishes is the symmetric matrix
 * {@link io.jethro.trading.riskpnl.HedgeMath#betaHedge} consumes.
 */
class HedgeStreamCovarianceTest {

    /** Span 4 → a pair speaks on its 4th joint return. No history, so no seed. */
    private static HedgeStreamCovariance basis() {
        return new HedgeStreamCovariance(new StreamCovariance(new StreamCovariance.Params(4)),
                null, null, 1_000L);
    }

    /** Deterministic alternating +1%/−1% walk, so every name genuinely moves every interval. */
    private static java.util.function.Function<String, Optional<BigDecimal>> prices(
            Map<String, BigDecimal> book) {
        return id -> Optional.ofNullable(book.get(id));
    }

    @Test
    void reportsNothingUntilThePairsAreWarm() {
        var basis = basis();
        var book = new java.util.LinkedHashMap<String, BigDecimal>();
        book.put("ES", new BigDecimal("5000"));
        book.put("STOCK", new BigDecimal("100"));
        basis.observe(book.keySet(), prices(book));
        assertTrue(basis.snapshot().isEmpty(), "one price is not yet a return");

        // Four more synchronised samples → four joint returns → the span is reached.
        for (int i = 1; i <= 4; i++) {
            book.put("ES", new BigDecimal(5000 + i));
            book.put("STOCK", new BigDecimal(100 + i));
            basis.observe(book.keySet(), prices(book));
        }
        var cov = basis.snapshot().orElseThrow();
        assertEquals(List.of("ES", "STOCK"), cov.instruments());
        assertEquals(5, cov.observations(), "observations are SAMPLING INTERVALS, not sessions");
        assertEquals(cov.sigma()[0][1], cov.sigma()[1][0], 0.0, "the matrix is symmetric");
        assertTrue(cov.sigma()[0][0] > 0 && cov.sigma()[1][1] > 0);
    }

    @Test
    void aNameThatJoinsLateIsNeverGivenAFabricatedCell() {
        // LATE has no measured pair with ES/STOCK yet. Filling those cells with zero would read as
        // "LATE does not move with the book" and silently shrink both ρ² and the hedge — so LATE is
        // simply not admitted until its own pairs are measured.
        var basis = basis();
        var book = new java.util.LinkedHashMap<String, BigDecimal>();
        for (int i = 0; i <= 5; i++) {
            book.put("ES", new BigDecimal(5000 + i));
            book.put("STOCK", new BigDecimal(100 + i));
            basis.observe(book.keySet(), prices(book));
        }
        book.put("LATE", new BigDecimal("40"));
        basis.observe(book.keySet(), prices(book));
        var cov = basis.snapshot().orElseThrow();
        assertFalse(cov.instruments().contains("LATE"), "an unmeasured pair is never a zero");
        assertEquals(2, cov.instruments().size());
    }

    @Test
    void aNameWithNoPriceContributesNothingRatherThanAZeroReturn() {
        var basis = basis();
        var names = List.of("ES", "STOCK", "NOPRICE");
        var book = new java.util.LinkedHashMap<String, BigDecimal>();
        for (int i = 0; i <= 5; i++) {
            book.put("ES", new BigDecimal(5000 + i));
            book.put("STOCK", new BigDecimal(100 + i));
            basis.observe(names, prices(book));
        }
        var cov = basis.snapshot().orElseThrow();
        assertEquals(List.of("ES", "STOCK"), cov.instruments());
    }

    @Test
    void aDisabledBasisPublishesNothing() {
        var off = new HedgeStreamCovariance(null, null, null, 1_000L);
        off.observe(List.of("ES"), id -> Optional.of(new BigDecimal("5000")));
        assertTrue(off.snapshot().isEmpty());
    }
}
