package io.jethro.app.fusion;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0079 — the portfolio diversification multiplier, on worked numbers computed by hand.
 *
 * <p>Setup used throughout: names priced at 100.00 with contract multiplier 1, each targeted at 1,000
 * units ⇒ a signed USD notional of exactly $100,000 per name, and a daily return vol of 2% per name
 * (Σᵢᵢ = 0.0004). Under equicorrelation ρ the closed forms are
 * {@code σ_indep = e·σ·√N} and {@code σ_actual = e·σ·√(N + N(N−1)ρ)}, so
 * {@code PDM = √(N / (N + N(N−1)ρ)) = 1/√(1 + (N−1)ρ)}.
 */
class PortfolioRiskNormaliserTest {

    private static final double SIGMA = 0.02;
    private static final double VARIANCE = SIGMA * SIGMA; // 0.0004
    private static final BigDecimal PRICE = new BigDecimal("100.00");
    private static final BigDecimal ONE_LOT = new BigDecimal("1000.000000"); // ⇒ $100,000 notional
    private static final Function<String, BigDecimal> MULT_ONE = id -> BigDecimal.ONE;
    /** Wide band + full adjustment: the recomputed delta is the whole gap from a flat book. */
    private static final FusionPlanner.Params PARAMS =
            new FusionPlanner.Params(0.5, new BigDecimal("50000"), 0.0, 1.0);

    /** Equicorrelated covariance over the given names: Σᵢⱼ = ρ·σ², Σᵢᵢ = σ². */
    private static ReturnCovarianceSource equicorrelated(List<String> names, double rho) {
        return (a, b) -> {
            if (!names.contains(a) || !names.contains(b)) {
                return OptionalDouble.empty();
            }
            return OptionalDouble.of(a.equals(b) ? VARIANCE : rho * VARIANCE);
        };
    }

    private static FusionPlanner.Target target(String instrument, BigDecimal qty) {
        return new FusionPlanner.Target(instrument, -18.0, 2, 1.0, 1.0, PRICE, qty,
                BigDecimal.ZERO, qty, List.of());
    }

    private static List<FusionPlanner.Target> book(List<String> names) {
        List<FusionPlanner.Target> out = new ArrayList<>();
        for (String n : names) {
            out.add(target(n, ONE_LOT.negate())); // all short — the one-bet cross-section
        }
        return out;
    }

    /**
     * Worked example 1 — three perfectly correlated names, each $100,000 short at σ = 2%/day.
     * <pre>
     *   σ_indep  = 100000 × 0.02 × √3           = 2000 × 1.7320508 = $3,464.1016
     *   σ_actual = 3 × 100000 × 0.02            =                    $6,000.0000
     *   PDM      = 3464.1016 / 6000             = 0.5773503 = 1/√3
     * </pre>
     * Each $100,000 target becomes $57,735.03, and the book's σ falls to
     * {@code 3 × 57735.03 × 0.02 = $3,464.10} — exactly the risk the per-name budget implied.
     */
    @Test
    void threePerfectlyCorrelatedNamesAreScaledToOneBudgetOfRisk() {
        List<String> names = List.of("AAA", "BBB", "CCC");
        var scaled = PortfolioRiskNormaliser.apply(book(names), MULT_ONE, equicorrelated(names, 1.0), PARAMS);

        assertEquals(3, scaled.coveredNames());
        assertEquals(1.0 / Math.sqrt(3), scaled.multiplier(), 1e-12);

        // -1000 × (1/√3) = -577.350269..., HALF_EVEN at 6dp ⇒ -577.350269
        for (FusionPlanner.Target t : scaled.targets()) {
            assertEquals(new BigDecimal("-577.350269"), t.targetQty());
            assertEquals(new BigDecimal("-577.350269"), t.deltaQty()); // flat book, full adjustment
        }

        // The scaled book's σ equals the independent-book σ the per-name budget already implied.
        double scaledSigma = 3 * 577.350269 * PRICE.doubleValue() * SIGMA;
        assertEquals(3 * 100_000.0 * SIGMA / Math.sqrt(3), scaledSigma, 0.01);
    }

    /** Worked example 2 — the same three names, ρ = 0. σ_indep = σ_actual ⇒ PDM = 1, book untouched. */
    @Test
    void uncorrelatedNamesAreLeftExactlyAsPlanned() {
        List<String> names = List.of("AAA", "BBB", "CCC");
        List<FusionPlanner.Target> planned = book(names);
        var scaled = PortfolioRiskNormaliser.apply(planned, MULT_ONE, equicorrelated(names, 0.0), PARAMS);

        assertEquals(1.0, scaled.multiplier(), 1e-12);
        assertSame(planned, scaled.targets()); // identity, not merely equal — no rounding applied
    }

    /**
     * Worked example 3 — ρ = 0.5 over 4 names: PDM = 1/√(1 + 3×0.5) = 1/√2.5 = 0.6324555.
     * −1000 × 0.6324555 = −632.455532.
     */
    @Test
    void partiallyCorrelatedBookScalesByTheClosedForm() {
        List<String> names = List.of("AAA", "BBB", "CCC", "DDD");
        var scaled = PortfolioRiskNormaliser.apply(book(names), MULT_ONE, equicorrelated(names, 0.5), PARAMS);

        assertEquals(1.0 / Math.sqrt(2.5), scaled.multiplier(), 1e-12);
        assertEquals(new BigDecimal("-632.455532"), scaled.targets().get(0).targetQty());
    }

    /**
     * A negatively-correlated (internally hedged) book has σ_actual &lt; σ_indep, so the raw ratio
     * exceeds 1. It is capped: the control may shrink the book, never grow it (the ADR-0076 property).
     */
    @Test
    void anInternallyHedgedBookIsCappedAndNeverLevered() {
        List<String> names = List.of("AAA", "BBB");
        ReturnCovarianceSource cov = (a, b) -> {
            if (!names.contains(a) || !names.contains(b)) {
                return OptionalDouble.empty();
            }
            return OptionalDouble.of(a.equals(b) ? VARIANCE : -0.9 * VARIANCE);
        };
        List<FusionPlanner.Target> planned = book(names);
        var scaled = PortfolioRiskNormaliser.apply(planned, MULT_ONE, cov, PARAMS);

        assertEquals(1.0, scaled.multiplier(), 1e-12);
        assertSame(planned, scaled.targets());
    }

    /** An uncovered name makes no claim: it is excluded from the sums AND left unscaled. */
    @Test
    void uncoveredNamesAreExcludedFromTheSumsAndLeftUnscaled() {
        List<String> covered = List.of("AAA", "BBB", "CCC");
        List<FusionPlanner.Target> planned = new ArrayList<>(book(covered));
        planned.add(target("UNCOVERED", ONE_LOT.negate()));

        var scaled = PortfolioRiskNormaliser.apply(planned, MULT_ONE, equicorrelated(covered, 1.0), PARAMS);

        assertEquals(3, scaled.coveredNames()); // the fourth name never entered the estimate
        assertEquals(1.0 / Math.sqrt(3), scaled.multiplier(), 1e-12);
        Map<String, BigDecimal> byName = new java.util.LinkedHashMap<>();
        scaled.targets().forEach(t -> byName.put(t.instrument(), t.targetQty()));
        assertEquals(new BigDecimal("-577.350269"), byName.get("AAA"));
        assertEquals(ONE_LOT.negate(), byName.get("UNCOVERED")); // untouched — nothing measured it
    }

    /** No measurement at all ⇒ the planned book routes exactly as before this change. */
    @Test
    void noCovarianceLeavesTheBookUntouched() {
        List<FusionPlanner.Target> planned = book(List.of("AAA", "BBB", "CCC"));
        var scaled = PortfolioRiskNormaliser.apply(planned, MULT_ONE, ReturnCovarianceSource.NONE, PARAMS);

        assertEquals(1.0, scaled.multiplier(), 1e-12);
        assertEquals(0, scaled.coveredNames());
        assertSame(planned, scaled.targets());
    }

    /** One covered name cannot be concentrated against itself — nothing to normalise. */
    @Test
    void aSingleNameIsNeverScaled() {
        List<String> names = List.of("AAA");
        List<FusionPlanner.Target> planned = book(names);
        var scaled = PortfolioRiskNormaliser.apply(planned, MULT_ONE, equicorrelated(names, 1.0), PARAMS);

        assertEquals(1.0, scaled.multiplier(), 1e-12);
        assertSame(planned, scaled.targets());
    }

    /**
     * The contract multiplier is part of the notional (ADR-0078): an ES-shaped name at multiplier 50
     * carries 50× the USD risk of an equity at the same quantity, and the normaliser must weight it
     * that way. Here one big contract dominates two small equities, so the concentration — and hence
     * the multiplier — differs from the equal-notional case.
     */
    @Test
    void theContractMultiplierEntersTheNotional() {
        List<String> names = List.of("AAA", "BBB", "FUT");
        Function<String, BigDecimal> mult =
                id -> "FUT".equals(id) ? new BigDecimal("50") : BigDecimal.ONE;
        List<FusionPlanner.Target> planned = book(names);

        var withMultiplier = PortfolioRiskNormaliser.apply(planned, mult, equicorrelated(names, 1.0), PARAMS);
        var asIfAllShares = PortfolioRiskNormaliser.apply(planned, MULT_ONE, equicorrelated(names, 1.0), PARAMS);

        // ρ=1, notionals (1, 1, 50) × $100k: σ_indep ∝ √(1+1+2500)=√2502, σ_actual ∝ 52.
        assertEquals(Math.sqrt(2502.0) / 52.0, withMultiplier.multiplier(), 1e-12);
        assertNotEquals(asIfAllShares.multiplier(), withMultiplier.multiplier(), 1e-9);
        assertTrue(withMultiplier.multiplier() > asIfAllShares.multiplier(),
                "a book dominated by one name is LESS diversified-away than three equal ones, "
                        + "so it is scaled down less");
    }

    /** A flat target carries no risk, so it neither enters the estimate nor is scaled. */
    @Test
    void flatTargetsAreIgnored() {
        List<String> names = List.of("AAA", "BBB", "CCC");
        List<FusionPlanner.Target> planned = new ArrayList<>(book(names));
        planned.add(target("DDD", BigDecimal.ZERO));

        var scaled = PortfolioRiskNormaliser.apply(planned, MULT_ONE,
                equicorrelated(List.of("AAA", "BBB", "CCC", "DDD"), 1.0), PARAMS);

        assertEquals(3, scaled.coveredNames());
        assertEquals(1.0 / Math.sqrt(3), scaled.multiplier(), 1e-12);
    }

    /**
     * Scaling is uniform and positive, so no name changes side — this is a size control, not a view.
     * A lopsided book (four short, one long, ρ = 0.9) is still net-concentrated:
     * {@code σ_indep² ∝ 5}; off-diagonals = 6 short-short pairs (+0.9 each, twice) − 4 short-long pairs
     * (−0.9 each, twice) = +10.8 − 7.2 = +3.6 ⇒ {@code σ_actual² ∝ 8.6}, PDM = √5/√8.6 ≈ 0.7625.
     */
    @Test
    void scalingPreservesEveryNamesSide() {
        List<String> names = List.of("AAA", "BBB", "CCC", "DDD", "EEE");
        List<FusionPlanner.Target> planned = List.of(
                target("AAA", ONE_LOT.negate()), target("BBB", ONE_LOT.negate()),
                target("CCC", ONE_LOT.negate()), target("DDD", ONE_LOT.negate()),
                target("EEE", ONE_LOT)); // the one offsetting name

        var scaled = PortfolioRiskNormaliser.apply(planned, MULT_ONE, equicorrelated(names, 0.9), PARAMS);

        assertEquals(Math.sqrt(5.0) / Math.sqrt(8.6), scaled.multiplier(), 1e-12);
        assertTrue(scaled.multiplier() < 1.0);
        for (int i = 0; i < 4; i++) {
            assertEquals(-1, scaled.targets().get(i).targetQty().signum());
        }
        assertEquals(1, scaled.targets().get(4).targetQty().signum());
    }
}
