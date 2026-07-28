package io.jethro.app.fusion;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0083 — the volatility-split per-name budget, on worked numbers computed by hand.
 *
 * <h2>The worked example (asserted exactly in {@link #splitsTheBudgetByInverseVolatility()})</h2>
 * Three names, all priced at $100.00 with contract multiplier 1, all at a typical forecast (so each
 * would take the flat budget of $10,000 under the old rule ⇒ 100 units each), with measured daily σ of
 * 2%, 1% and 0.5%:
 * <pre>
 *   σ_ref = 3 / (1/0.02 + 1/0.01 + 1/0.005) = 3 / 350 = 0.008571428571…
 *   k_A   = 0.008571428571… / 0.020 = 0.428571428571…
 *   k_B   = 0.008571428571… / 0.010 = 0.857142857142…
 *   k_C   = 0.008571428571… / 0.005 = 1.714285714285…
 *   Σ k   = 3.000000  exactly                          ⇒ the budgets are unchanged in total
 * </pre>
 * so the quantities become 42.857143, 85.714286 and 171.428571 (HALF_EVEN at scale 6) and the budgets
 * $4,285.71 / $8,571.43 / $17,142.86 — still $30,000 in total. Each name's standalone daily risk is
 * {@code notional × σ}: 4285.7143×0.02 = 8571.4286×0.01 = 17142.8571×0.005 = $85.71 — identical, which
 * is the whole point. Under the flat rule they were $200 / $100 / $50, i.e. name A alone carried 57% of
 * the book's standalone risk while holding a third of its cash.
 */
class VolatilityBudgetTest {

    private static final BigDecimal PRICE = new BigDecimal("100.00");
    private static final Function<String, BigDecimal> MULT_ONE = id -> BigDecimal.ONE;
    /** No band + full adjustment: the recomputed delta is the whole gap from a flat book. */
    private static final FusionPlanner.Params PARAMS =
            new FusionPlanner.Params(0.5, new BigDecimal("10000"), 0.0, 1.0);

    /** Diagonal covariance from a name → daily σ map; names outside it are uncovered. */
    private static ReturnCovarianceSource diagonal(Map<String, Double> sigmaByName) {
        return (a, b) -> {
            if (!a.equals(b)) {
                return OptionalDouble.of(0.0); // off-diagonals are irrelevant here
            }
            Double s = sigmaByName.get(a);
            return s == null ? OptionalDouble.empty() : OptionalDouble.of(s * s);
        };
    }

    private static FusionPlanner.Target target(String instrument, String qty) {
        BigDecimal q = new BigDecimal(qty);
        return new FusionPlanner.Target(instrument, 10.0, 2, 1.0, 1.0, PRICE, q, BigDecimal.ZERO, q, List.of());
    }

    private static FusionPlanner.Target find(List<FusionPlanner.Target> targets, String instrument) {
        return targets.stream().filter(t -> t.instrument().equals(instrument)).findFirst().orElseThrow();
    }

    @Test
    void splitsTheBudgetByInverseVolatility() {
        // $10,000 of budget at price 100 ⇒ 100 units each under the flat rule.
        var targets = List.of(target("A", "100.000000"), target("B", "100.000000"),
                target("C", "100.000000"));
        var cov = diagonal(Map.of("A", 0.02, "B", 0.01, "C", 0.005));

        var scaled = VolatilityBudget.apply(targets, MULT_ONE, cov, 10.0, PARAMS);

        assertEquals(3, scaled.coveredNames());
        assertEquals(1.0, scaled.leverCap(), 0, "pure redistribution — the cap must not bind");
        assertEquals(4.0, scaled.dispersion(), 1e-12, "max σ / min σ = 0.02 / 0.005");
        assertEquals(new BigDecimal("42.857143"), find(scaled.targets(), "A").targetQty());
        assertEquals(new BigDecimal("85.714286"), find(scaled.targets(), "B").targetQty());
        assertEquals(new BigDecimal("171.428571"), find(scaled.targets(), "C").targetQty());
        // The delta is recomputed against the re-budgeted target, not the old one.
        assertEquals(new BigDecimal("42.857143"), find(scaled.targets(), "A").deltaQty());
    }

    @Test
    void leavesTheCoveredBudgetsSummingToWhatTheySummedToBefore() {
        var targets = List.of(target("A", "100.000000"), target("B", "100.000000"),
                target("C", "100.000000"));
        var cov = diagonal(Map.of("A", 0.02, "B", 0.01, "C", 0.005));

        var scaled = VolatilityBudget.apply(targets, MULT_ONE, cov, 10.0, PARAMS);

        BigDecimal before = BigDecimal.ZERO;
        for (var t : targets) {
            before = before.add(t.targetQty().multiply(t.price()));
        }
        BigDecimal after = BigDecimal.ZERO;
        for (var t : scaled.targets()) {
            after = after.add(t.targetQty().multiply(t.price()));
        }
        assertEquals(0, new BigDecimal("30000.00").compareTo(before), "flat rule: 3 × $10,000");
        assertEquals(0, before.compareTo(after), "redistribution invents no cash: " + after);
    }

    @Test
    void equalisesEachNamesStandaloneRisk() {
        var targets = List.of(target("A", "100.000000"), target("B", "100.000000"),
                target("C", "100.000000"));
        var sigmas = Map.of("A", 0.02, "B", 0.01, "C", 0.005);

        var scaled = VolatilityBudget.apply(targets, MULT_ONE, diagonal(sigmas), 10.0, PARAMS);

        for (var t : scaled.targets()) {
            double risk = t.targetQty().multiply(t.price()).doubleValue() * sigmas.get(t.instrument());
            assertEquals(85.714285, risk, 1e-5, t.instrument() + " must carry the same standalone risk");
        }
    }

    @Test
    void neverGrowsTheBookWhenTheStrongestViewsSitInTheQuietestNames() {
        // A tiny position in the volatile name and a large one in the quiet name: inverse-σ budgeting
        // would push MORE cash into the name that already holds most of it, so the cap must bite.
        var targets = List.of(target("LOUD", "1.000000"), target("QUIET", "1000.000000"));
        var cov = diagonal(Map.of("LOUD", 0.04, "QUIET", 0.005));

        var scaled = VolatilityBudget.apply(targets, MULT_ONE, cov, 0.0, PARAMS);

        double before = 1.0 * 100 + 1000.0 * 100;
        double after = 0;
        for (var t : scaled.targets()) {
            after += Math.abs(t.targetQty().multiply(t.price()).doubleValue());
        }
        assertTrue(scaled.leverCap() < 1.0, "the cap must bind here, got " + scaled.leverCap());
        assertTrue(after <= before + 1e-6, "gross grew: " + after + " > " + before);
    }

    @Test
    void leavesAnUncoveredNameOnTheFlatDial() {
        var targets = List.of(target("A", "100.000000"), target("B", "100.000000"),
                target("UNKNOWN", "100.000000"));
        var cov = diagonal(Map.of("A", 0.02, "B", 0.01)); // UNKNOWN has no measured σ

        var scaled = VolatilityBudget.apply(targets, MULT_ONE, cov, 10.0, PARAMS);

        assertEquals(2, scaled.coveredNames());
        assertEquals(new BigDecimal("100.000000"), find(scaled.targets(), "UNKNOWN").targetQty(),
                "no measurement, no claim (ADR-0016 / invariant 7)");
    }

    @Test
    void winsorisationBoundsWhatOneDegenerateEstimateCanClaim() {
        // NEARZERO's σ is 1/1000 of the others'. Unclipped it would take ~1000× the budget. Six names
        // at p = 20 is the smallest cross-section where nearest-rank selects an interior lower rank
        // (⌈0.2 × 6⌉ = 2), which is exactly the threshold the class documents.
        var targets = List.of(target("A", "100.000000"), target("B", "100.000000"),
                target("C", "100.000000"), target("D", "100.000000"), target("E", "100.000000"),
                target("NEARZERO", "100.000000"));
        var cov = diagonal(Map.of("A", 0.02, "B", 0.02, "C", 0.02, "D", 0.02, "E", 0.02,
                "NEARZERO", 0.00002));

        var clipped = VolatilityBudget.apply(targets, MULT_ONE, cov, 20.0, PARAMS);
        var unclipped = VolatilityBudget.apply(targets, MULT_ONE, cov, 0.0, PARAMS);

        BigDecimal clippedQty = find(clipped.targets(), "NEARZERO").targetQty();
        BigDecimal unclippedQty = find(unclipped.targets(), "NEARZERO").targetQty();
        assertTrue(clippedQty.compareTo(unclippedQty) < 0,
                "winsorising must shrink the degenerate name's claim: " + clippedQty + " vs " + unclippedQty);
        assertEquals(1.0, clipped.dispersion(), 1e-12, "clipped up to the others ⇒ all σ equal");
        // The structural bound holds even unclipped: the factors sum to |C|, so no name can take more
        // than the whole covered budget — here 6 × $10,000 at price 100 ⇒ 6,000 units.
        assertTrue(unclippedQty.compareTo(new BigDecimal("600.000000")) <= 0,
                "Σk = |C| bounds even a degenerate name: " + unclippedQty);
    }

    @Test
    void winsoriseClipsBothTailsAtTheNearestRankPercentile() {
        var sigmas = List.of(0.001, 0.010, 0.020, 0.030, 0.500);
        // n = 5, p = 20 ⇒ lo index = ceil(1.0) − 1 = 0 ⇒ 0.001 (its own bound — below the threshold
        // ⌈p/100·n⌉ ≥ 2, so the lower tail is NOT clipped); hi index = ceil(4.0) − 1 = 3 ⇒ 0.030.
        assertEquals(List.of(0.001, 0.010, 0.020, 0.030, 0.030), VolatilityBudget.winsorise(sigmas, 20.0));
        // p = 40 ⇒ lo index = ceil(2.0) − 1 = 1 ⇒ 0.010; hi index = ceil(3.0) − 1 = 2 ⇒ 0.020.
        assertEquals(List.of(0.010, 0.010, 0.020, 0.020, 0.020), VolatilityBudget.winsorise(sigmas, 40.0));
        assertEquals(sigmas, VolatilityBudget.winsorise(sigmas, 0.0), "0 disables clipping");
    }

    @Test
    void leavesTheBookUntouchedWithNothingMeasured() {
        var targets = List.of(target("A", "100.000000"), target("B", "100.000000"));

        var scaled = VolatilityBudget.apply(targets, MULT_ONE, ReturnCovarianceSource.NONE, 10.0, PARAMS);

        assertSame(targets, scaled.targets());
        assertEquals(0, scaled.coveredNames());
        assertEquals(1.0, scaled.leverCap(), 0);
    }

    @Test
    void leavesTheBookUntouchedWithASingleCoveredName() {
        var targets = List.of(target("A", "100.000000"), target("B", "100.000000"));
        var scaled = VolatilityBudget.apply(targets, MULT_ONE, diagonal(Map.of("A", 0.02)), 10.0, PARAMS);

        assertSame(targets, scaled.targets(), "one name cannot be unequal to itself");
        assertEquals(1, scaled.coveredNames());
    }

    @Test
    void skipsFlatTargetsAndNamesWithNoContractSpec() {
        var targets = List.of(target("A", "100.000000"), target("FLAT", "0.000000"),
                target("NOSPEC", "100.000000"), target("B", "100.000000"));
        var cov = diagonal(Map.of("A", 0.02, "FLAT", 0.02, "NOSPEC", 0.02, "B", 0.01));
        Function<String, BigDecimal> mult = id -> "NOSPEC".equals(id) ? null : BigDecimal.ONE;

        var scaled = VolatilityBudget.apply(targets, mult, cov, 10.0, PARAMS);

        assertEquals(2, scaled.coveredNames(), "only A and B carry a budget that can be re-split");
        assertEquals(new BigDecimal("0.000000"), find(scaled.targets(), "FLAT").targetQty());
        assertEquals(new BigDecimal("100.000000"), find(scaled.targets(), "NOSPEC").targetQty());
    }

    @Test
    void preservesEverySignSoNoNameFlipsSide() {
        var longName = target("A", "100.000000");
        var shortName = new FusionPlanner.Target("B", -10.0, 2, 1.0, 1.0, PRICE,
                new BigDecimal("-100.000000"), BigDecimal.ZERO, new BigDecimal("-100.000000"), List.of());
        var cov = diagonal(Map.of("A", 0.02, "B", 0.005));

        var scaled = VolatilityBudget.apply(List.of(longName, shortName), MULT_ONE, cov, 10.0, PARAMS);

        assertTrue(find(scaled.targets(), "A").targetQty().signum() > 0);
        assertTrue(find(scaled.targets(), "B").targetQty().signum() < 0);
    }

    @Test
    void leavesAnAlreadyRiskEqualBookUntouched() {
        var targets = List.of(target("A", "100.000000"), target("B", "100.000000"));
        var cov = diagonal(Map.of("A", 0.02, "B", 0.02));

        var scaled = VolatilityBudget.apply(targets, MULT_ONE, cov, 10.0, PARAMS);

        assertEquals(1.0, scaled.dispersion(), 1e-12);
        assertEquals(new BigDecimal("100.000000"), find(scaled.targets(), "A").targetQty());
        assertEquals(new BigDecimal("100.000000"), find(scaled.targets(), "B").targetQty());
    }
}
