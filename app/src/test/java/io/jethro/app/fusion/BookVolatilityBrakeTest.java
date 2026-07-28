package io.jethro.app.fusion;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.OptionalDouble;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0104 — the book-level volatility brake, on worked numbers computed by hand.
 *
 * <p>Setup used throughout: names priced at 100.00 with contract multiplier 1, so a target of {@code q}
 * units is a signed USD notional of exactly {@code 100q}; daily return vol 2% per name
 * (Σᵢᵢ = 0.0004). For a ONE-name book that makes {@code σ_planned = 0.02 × |notional|} — a $100,000
 * target is σ = $2,000/day, a $400,000 target is σ = $8,000/day.
 */
class BookVolatilityBrakeTest {

    private static final double SIGMA = 0.02;
    private static final double VARIANCE = SIGMA * SIGMA; // 0.0004
    private static final BigDecimal PRICE = new BigDecimal("100.00");
    private static final Function<String, BigDecimal> MULT_ONE = id -> BigDecimal.ONE;
    /** No band + full adjustment: the recomputed delta is the whole gap from a flat book. */
    private static final FusionPlanner.Params PARAMS =
            new FusionPlanner.Params(0.5, new BigDecimal("50000"), 0.0, 1.0);

    private static ReturnCovarianceSource equicorrelated(List<String> names, double rho) {
        return (a, b) -> {
            if (!names.contains(a) || !names.contains(b)) {
                return OptionalDouble.empty();
            }
            return OptionalDouble.of(a.equals(b) ? VARIANCE : rho * VARIANCE);
        };
    }

    private static FusionPlanner.Target target(String instrument, String qty) {
        BigDecimal q = new BigDecimal(qty);
        return new FusionPlanner.Target(instrument, -18.0, 2, 1.0, 1.0, PRICE, q, BigDecimal.ZERO, q, List.of());
    }

    private static BookVolatilityBrake.Result apply(BookVolatilityBrake brake,
                                                    List<FusionPlanner.Target> book,
                                                    ReturnCovarianceSource cov) {
        return brake.apply(book, MULT_ONE, cov, PARAMS);
    }

    /** σ of a one-name book is |notional| × σ_name: 1,000 units at 100.00 ⇒ $100,000 ⇒ $2,000/day. */
    @Test
    void measuresThePlannedSigmaOfAOneNameBook() {
        var cov = equicorrelated(List.of("AAA"), 0.0);
        var brake = new BookVolatilityBrake(new BookVolatilityBrake.Params(8, 1));
        var r = apply(brake, List.of(target("AAA", "1000.000000")), cov);
        assertEquals(2000.0, r.plannedSigmaUsd(), 1e-9);
    }

    /**
     * Two equicorrelated names: σ = e·σ_name·√(N + N(N−1)ρ). At N=2, ρ=0.5, e=$100,000:
     * 100000 × 0.02 × √3 = 2000√3 = 3464.101615…
     */
    @Test
    void measuresThePlannedSigmaAtTheMeasuredCorrelation() {
        var cov = equicorrelated(List.of("AAA", "BBB"), 0.5);
        var brake = new BookVolatilityBrake(new BookVolatilityBrake.Params(8, 1));
        var r = apply(brake, List.of(target("AAA", "1000.000000"), target("BBB", "1000.000000")), cov);
        assertEquals(2000.0 * Math.sqrt(3.0), r.plannedSigmaUsd(), 1e-6);
    }

    /** Below the minimum sample the brake makes no claim — the book is byte-identical. */
    @Test
    void isSilentUntilItHasEnoughObservations() {
        var cov = equicorrelated(List.of("AAA"), 0.0);
        var brake = new BookVolatilityBrake(new BookVolatilityBrake.Params(120, 30));
        List<FusionPlanner.Target> book = List.of(target("AAA", "9000.000000"));
        for (int i = 0; i < 29; i++) {
            var r = apply(brake, book, cov);
            assertSame(book, r.targets(), "warming — the book must be untouched");
            assertEquals(1.0, r.multiplier(), 1e-12);
            assertNull(r.referenceSigmaUsd());
        }
        assertEquals(29, brake.samples());
    }

    /**
     * Worked: three cycles plan $100,000 (σ = 2,000), the fourth plans $400,000 (σ = 8,000). The series
     * is {2000, 2000, 2000, 8000}, whose median is (2000+2000)/2 = 2,000. brake = 2000/8000 = 0.25, so
     * the 4,000-unit target is scaled to exactly 1,000 units — and the braked book's σ is 2,000 = σ_ref.
     */
    @Test
    void capsTheBookAtTheMedianOfItsOwnPlannedRisk() {
        var cov = equicorrelated(List.of("AAA"), 0.0);
        var brake = new BookVolatilityBrake(new BookVolatilityBrake.Params(8, 3));
        for (int i = 0; i < 3; i++) {
            apply(brake, List.of(target("AAA", "1000.000000")), cov);
        }
        var r = apply(brake, List.of(target("AAA", "4000.000000")), cov);
        assertEquals(0.25, r.multiplier(), 1e-12);
        assertEquals(2000.0, r.referenceSigmaUsd(), 1e-9);
        assertEquals(8000.0, r.plannedSigmaUsd(), 1e-9);
        assertEquals(new BigDecimal("1000.000000"), r.targets().get(0).targetQty());
        // and the delta is recomputed against the braked target, not the planned one
        assertEquals(new BigDecimal("1000.000000"), r.targets().get(0).deltaQty());
    }

    /** A book quieter than its own median is left exactly alone — the brake is one-way. */
    @Test
    void neverGrowsTheBook() {
        var cov = equicorrelated(List.of("AAA"), 0.0);
        var brake = new BookVolatilityBrake(new BookVolatilityBrake.Params(8, 3));
        for (int i = 0; i < 3; i++) {
            apply(brake, List.of(target("AAA", "4000.000000")), cov);
        }
        List<FusionPlanner.Target> quiet = List.of(target("AAA", "1000.000000"));
        var r = apply(brake, quiet, cov);
        assertEquals(1.0, r.multiplier(), 1e-12);
        assertSame(quiet, r.targets());
    }

    /**
     * No ratchet: the series records the RAW planned σ, never the braked one. Repeating the same
     * $400,000 plan produces the same 0.25 brake — not 0.25 compounded on itself.
     */
    @Test
    void doesNotRatchetOnItsOwnOutput() {
        var cov = equicorrelated(List.of("AAA"), 0.0);
        var brake = new BookVolatilityBrake(new BookVolatilityBrake.Params(8, 3));
        for (int i = 0; i < 3; i++) {
            apply(brake, List.of(target("AAA", "1000.000000")), cov);
        }
        var first = apply(brake, List.of(target("AAA", "4000.000000")), cov);
        var second = apply(brake, List.of(target("AAA", "4000.000000")), cov);
        assertEquals(0.25, first.multiplier(), 1e-12);
        assertEquals(0.25, second.multiplier(), 1e-12);
        assertEquals(new BigDecimal("1000.000000"), second.targets().get(0).targetQty());
    }

    /** Uniform and positive: a short book is scaled, never flipped. */
    @Test
    void preservesSideAndCrossSectionalShape() {
        var cov = equicorrelated(List.of("AAA", "BBB"), 0.0);
        var brake = new BookVolatilityBrake(new BookVolatilityBrake.Params(8, 3));
        for (int i = 0; i < 3; i++) {
            apply(brake, List.of(target("AAA", "500.000000"), target("BBB", "-500.000000")), cov);
        }
        var r = apply(brake, List.of(target("AAA", "2000.000000"), target("BBB", "-2000.000000")), cov);
        assertTrue(r.multiplier() < 1.0);
        assertEquals(1, r.targets().get(0).targetQty().signum());
        assertEquals(-1, r.targets().get(1).targetQty().signum());
        // ratio between the two names is unchanged — this is a size control, not a view
        assertEquals(r.targets().get(0).targetQty(), r.targets().get(1).targetQty().negate());
    }

    /** A name the covariance does not cover is left exactly as planned — no measurement, no claim. */
    @Test
    void leavesUncoveredNamesUntouched() {
        var cov = equicorrelated(List.of("AAA"), 0.0);
        var brake = new BookVolatilityBrake(new BookVolatilityBrake.Params(8, 3));
        for (int i = 0; i < 3; i++) {
            apply(brake, List.of(target("AAA", "1000.000000")), cov);
        }
        var r = apply(brake, List.of(target("AAA", "4000.000000"), target("ZZZ", "7777.000000")), cov);
        assertEquals(1, r.coveredNames());
        assertEquals(new BigDecimal("1000.000000"), r.targets().get(0).targetQty());
        assertEquals(new BigDecimal("7777.000000"), r.targets().get(1).targetQty());
    }

    /** No covariance at all, or an empty book, leaves everything byte-identical. */
    @Test
    void makesNoClaimWithoutAMeasurement() {
        var brake = new BookVolatilityBrake(new BookVolatilityBrake.Params(8, 1));
        List<FusionPlanner.Target> book = List.of(target("AAA", "1000.000000"));
        var r = apply(brake, book, ReturnCovarianceSource.NONE);
        assertSame(book, r.targets());
        assertEquals(1.0, r.multiplier(), 1e-12);
        assertEquals(0, brake.samples());
    }
}
