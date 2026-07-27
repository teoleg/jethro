package io.jethro.app.fusion;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0096 — the fused target book spans only the names the desk may actually put risk on plus the
 * names it already holds, so the book-level risk controls measure the book that can exist.
 *
 * <p>The first test is the money claim: a phantom sleeve the desk can never enter RAISES the ADR-0079
 * diversification multiplier, and that multiplier is applied uniformly to the names that do trade. The
 * rest pin the filter's semantics, in particular that it can never drop a held position.
 */
class ActionableBookTest {

    /**
     * The worked example behind the ADR, by hand.
     *
     * <p>Three names, each planned at a signed USD notional of {@code e = 10,000} and each with a daily
     * return variance of {@code σ² = 0.0004} (σ = 2%). A and B are the names the desk can trade and are
     * PERFECTLY correlated with each other; P is a phantom — a name in a different asset class the OOS
     * selector has vetoed, uncorrelated with both.
     *
     * <pre>
     *   Σ_AA = Σ_BB = Σ_PP = 0.0004      Σ_AB = Σ_BA = 0.0004 (ρ=1)      Σ_AP = Σ_BP = 0
     *
     *   real book {A,B}:      σ_indep² = 2·e²·0.0004               = 80,000
     *                         σ_actual² = 4·e²·0.0004              = 160,000
     *                         PDM = √(80,000/160,000)  = 1/√2      = 0.70710678…
     *
     *   with the phantom:     σ_indep² = 3·e²·0.0004               = 120,000
     *                         σ_actual² = 160,000 + e²·0.0004      = 200,000
     *                         PDM = √(120,000/200,000) = √0.6      = 0.77459667…
     * </pre>
     *
     * A and B are the only names carrying risk, they are one bet, and the honest multiplier on them is
     * {@code 1/√2}. The phantom lifts it to {@code √0.6} — the two tradable names are sized 9.5% larger
     * on the strength of a diversification the book will never actually have. That is the whole defect:
     * the control that exists to stop the name COUNT levering the owner's per-name dial was being fed a
     * name count that was mostly unreachable.
     */
    @Test
    void aPhantomSleeveInflatesTheDiversificationMultiplier() {
        ReturnCovarianceSource cov = (a, b) -> {
            if (a.equals("P") || b.equals("P")) {
                return a.equals(b) ? OptionalDouble.of(0.0004) : OptionalDouble.of(0.0); // uncorrelated
            }
            return OptionalDouble.of(0.0004); // A and B: unit correlation
        };
        List<Double> e2 = List.of(10_000.0, 10_000.0);
        List<Double> e3 = List.of(10_000.0, 10_000.0, 10_000.0);

        double real = PortfolioRiskNormaliser.multiplier(List.of("A", "B"), e2, cov);
        double withPhantom = PortfolioRiskNormaliser.multiplier(List.of("A", "B", "P"), e3, cov);

        assertEquals(1.0 / Math.sqrt(2.0), real, 1e-12, "two perfectly correlated names are one bet");
        assertEquals(Math.sqrt(0.6), withPhantom, 1e-12, "the hand-computed value with the phantom");
        assertTrue(withPhantom > real,
                "a name that can never be held raises the measured diversification of the book");
        // The size the phantom buys the tradable names, stated as a ratio so it is scale-free.
        assertEquals(1.0954451150103321, withPhantom / real, 1e-12);
    }

    @Test
    void anUnopenableNameWithNoPositionIsDroppedFromTheBook() {
        Map<String, List<Forecast>> forecasts = book("AAPL", "GS", "ZN");
        var out = FusionLifecycle.actionable(forecasts, Set.of(), name -> name.equals("AAPL"));

        assertEquals(Set.of("AAPL"), out.keySet(), "only the openable name survives");
    }

    @Test
    void aHeldNameIsNeverDroppedAndKeepsItsOwnForecast() {
        // The name the selector has turned against but the desk is still carrying. Dropping it would
        // strand the position outside every control that can work it down (ADR-0065/0086/0027), so it
        // stays in the book with its view untouched — the executor's reduce-only veto keeps doing the
        // rest of the job, exactly as before this change.
        Map<String, List<Forecast>> forecasts = book("AAPL", "SAP");
        var out = FusionLifecycle.actionable(forecasts, Set.of("SAP"), name -> name.equals("AAPL"));

        assertEquals(Set.of("AAPL", "SAP"), out.keySet());
        assertSame(forecasts.get("SAP"), out.get("SAP"), "a held name's forecast is passed through");
    }

    @Test
    void anOpenPredicateLeavesTheBookUnchanged() {
        Map<String, List<Forecast>> forecasts = book("AAPL", "GS", "ZN");

        assertEquals(forecasts.keySet(),
                FusionLifecycle.actionable(forecasts, Set.of(), name -> true).keySet(),
                "fail-open (no selector wired) must plan exactly the book it planned before");
        assertSame(forecasts, FusionLifecycle.actionable(forecasts, Set.of(), null),
                "no predicate ⇒ the same map, untouched");
    }

    @Test
    void theFilterOnlyEverShrinksTheBook() {
        Map<String, List<Forecast>> forecasts = book("AAPL", "GS", "ZN", "SAP");
        for (boolean openAll : new boolean[] {true, false}) {
            var out = FusionLifecycle.actionable(forecasts, Set.of("SAP"), name -> openAll);
            assertTrue(out.size() <= forecasts.size(), "never adds a name the sources did not emit");
            assertTrue(out.keySet().containsAll(Set.of("SAP")), "the held name is always present");
            assertFalse(out.containsKey("NVDA"), "no name is invented");
        }
    }

    private static Map<String, List<Forecast>> book(String... instruments) {
        Map<String, List<Forecast>> out = new java.util.LinkedHashMap<>();
        for (String id : instruments) {
            out.put(id, List.of(new Forecast("reversion", id, 10.0)));
        }
        return out;
    }
}
