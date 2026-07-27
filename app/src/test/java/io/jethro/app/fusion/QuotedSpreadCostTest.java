package io.jethro.app.fusion;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0099 — the round trip a name's own quote implies, and the one-way rule that puts it in front of
 * the desk blend for a name that has never filled.
 */
class QuotedSpreadCostTest {

    private static final EdgeGate.Params PARAMS = new EdgeGate.Params(2, 2.0);

    // ---- the estimator ------------------------------------------------------------------------

    @Test
    void wideQuoteCostsItsFullSpreadToRoundTrip() {
        // bid 99.90 / ask 100.10: 20000 × 0.20 / 200.00 = 20.0000 bps — worked by hand.
        assertThat(QuotedSpreadCost.roundTripBps(new BigDecimal("99.90"), new BigDecimal("100.10")))
                .hasValue(20.0);
    }

    @Test
    void tightQuoteCostsItsFullSpreadToRoundTrip() {
        // bid 99.99 / ask 100.01: 20000 × 0.02 / 200.00 = 2.0000 bps.
        assertThat(QuotedSpreadCost.roundTripBps(new BigDecimal("99.99"), new BigDecimal("100.01")))
                .hasValue(2.0);
    }

    @Test
    void lockedMarketIsAMeasuredZeroNotAnAbsentReading() {
        assertThat(QuotedSpreadCost.roundTripBps(new BigDecimal("100.00"), new BigDecimal("100.00")))
                .hasValue(0.0);
    }

    @Test
    void anAbsentOrImpossibleQuoteStatesNothing() {
        assertThat(QuotedSpreadCost.roundTripBps(null, new BigDecimal("100.10"))).isEmpty();
        assertThat(QuotedSpreadCost.roundTripBps(new BigDecimal("99.90"), null)).isEmpty();
        assertThat(QuotedSpreadCost.roundTripBps(BigDecimal.ZERO, new BigDecimal("100.10"))).isEmpty();
        // crossed book — ask below bid is not a cost, it is a bad tick
        assertThat(QuotedSpreadCost.roundTripBps(new BigDecimal("100.10"), new BigDecimal("99.90"))).isEmpty();
    }

    @Test
    void theRatioIsScaleFree() {
        // The same 20 bps touch on a $5,000 contract reads the same as on a $100 share.
        assertThat(QuotedSpreadCost.roundTripBps(new BigDecimal("4995.00"), new BigDecimal("5005.00")))
                .hasValue(20.0);
    }

    // ---- the merge rule -----------------------------------------------------------------------

    @Test
    void aMeasuredNameKeepsItsOwnMeasurementAndIgnoresTheQuote() {
        var measured = Map.of("AAPL", 0.96);
        var merged = QuotedSpreadCost.withQuotedFallback(measured, 1.48, Map.of("AAPL", 2.0));
        assertThat(merged).containsEntry("AAPL", 0.96); // a fill always beats a quote
    }

    @Test
    void anUnmeasuredWideNameIsChargedItsOwnQuoteInsteadOfTheBlend() {
        var merged = QuotedSpreadCost.withQuotedFallback(Map.of(), 1.48, Map.of("BRK.B", 20.0));
        assertThat(merged).containsEntry("BRK.B", 20.0);
    }

    @Test
    void anUnmeasuredTightNameIsNeverChargedLessThanTheBlend() {
        // One-way: a 0.44 bps quote does not buy a name a cheaper hurdle than it has today.
        var merged = QuotedSpreadCost.withQuotedFallback(Map.of(), 1.48, Map.of("EURUSD", 0.44));
        assertThat(merged).containsEntry("EURUSD", 1.48);
    }

    @Test
    void aNameWithNoQuoteIsLeftToTheBlend() {
        var merged = QuotedSpreadCost.withQuotedFallback(Map.of("AAPL", 0.96), 1.48, Map.of());
        assertThat(merged).containsOnlyKeys("AAPL");
    }

    @Test
    void nonFiniteQuotesAreDiscardedRatherThanCharged() {
        var quoted = new LinkedHashMap<String, Double>();
        quoted.put("BAD", Double.NaN);
        quoted.put("ALSOBAD", Double.POSITIVE_INFINITY);
        assertThat(QuotedSpreadCost.withQuotedFallback(Map.of(), 1.48, quoted)).isEmpty();
    }

    // ---- the properties the safety argument rests on -------------------------------------------

    @Test
    void everyPerNameHurdleIsAtLeastWhatItIsToday() {
        var measured = Map.of("AAPL", 0.96, "GOOGL", 20.11);
        var quoted = Map.of("AAPL", 2.0, "BRK.B", 20.0, "EURUSD", 0.44, "ES", 0.46);
        double blend = 1.48;
        var merged = QuotedSpreadCost.withQuotedFallback(measured, blend, quoted);
        for (var name : List.of("AAPL", "GOOGL", "BRK.B", "EURUSD", "ES")) {
            double today = measured.getOrDefault(name, blend);
            assertThat(merged.getOrDefault(name, blend))
                    .as("hurdle for %s may never fall", name)
                    .isGreaterThanOrEqualTo(today);
        }
    }

    @Test
    void theDeskWideVerdictCannotMoveBecauseTheMinimumCannotFall() {
        // The gate takes its desk-wide verdict at the cheapest round trip in map-plus-blend. Nothing
        // this adds is below the blend, so that minimum — and so the verdict — is untouched.
        var stats = List.of(stats("reversion", 500, 56, 8.81, 0.97));
        var measured = Map.of("NQ", -0.25);
        double blend = 1.48;
        var before = EdgeGate.evaluate(stats, blend, measured, PARAMS);
        var after = EdgeGate.evaluate(stats, blend,
                QuotedSpreadCost.withQuotedFallback(measured, blend, Map.of("BRK.B", 20.0, "EURUSD", 0.44)),
                PARAMS);
        assertThat(after.mayIncrease()).isEqualTo(before.mayIncrease());
        assertThat(after.sources().get(0).netEdgeBps()).isEqualTo(before.sources().get(0).netEdgeBps());
    }

    @Test
    void theWideNameLosesItsPermissionWhileTheCheapOnesKeepTheirs() {
        // The live shape: a +8.81 bps edge (SE 0.97) survives a 2 bps touch and cannot survive a 20 bps
        // one. Under the blend alone the wide name was permitted; charged its own quote it is not.
        var stats = List.of(stats("reversion", 500, 56, 8.81, 0.97));
        double blend = 1.48;
        var measured = Map.<String, Double>of();
        var quoted = Map.of("BRK.B", 20.0, "NVDA", 2.0);

        var before = EdgeGate.evaluate(stats, blend, measured, PARAMS);
        assertThat(before.mayIncrease("BRK.B")).isTrue(); // charged the blend it looks affordable

        var after = EdgeGate.evaluate(stats, blend,
                QuotedSpreadCost.withQuotedFallback(measured, blend, quoted), PARAMS);
        assertThat(after.mayIncrease("BRK.B")).isFalse(); // 20 bps to round-trip an 8.81 bps edge
        assertThat(after.mayIncrease("NVDA")).isTrue();   // 2 bps still clears with significance
        assertThat(after.mayIncrease()).isTrue();         // and the desk is not shut down by it
    }

    /**
     * A source reading with a stated standard error: the ADR-0077 SE is
     * {@code stdCohortMean / sqrt(cohorts)}, so the cohort dispersion is set to reproduce {@code seBps}.
     */
    private static io.jethro.app.signal.SignalScoring.Stats stats(String source, int resolved, int cohorts,
                                                                  double avgBps, double seBps) {
        return new io.jethro.app.signal.SignalScoring.Stats(source, resolved, 0, 0, 0, 0, 0.0, avgBps,
                0.0, cohorts, seBps * Math.sqrt(cohorts));
    }
}
