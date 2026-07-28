package io.jethro.app.signal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * ADR-0120 — a cohort is one SWEEP of the cross-section, identified by the names in it rather than by
 * a clock gap.
 *
 * <p>What is pinned here is the grouping that sets the DEGREES OF FREEDOM of the edge gate, which is
 * the control deciding whether the desk may put risk on at all. Getting it wrong in the loose direction
 * lets a single draw of the market be counted several times over, which is exactly how a desk starts
 * trading on noise — so the rule is asserted on its mechanism (a name appears at most once per cohort),
 * not on a tuned window.
 *
 * <p>These are dimensionless returns, not money, so they are doubles by design (see
 * {@link SignalScoring}); the assertions are exact arithmetic read back at machine precision.
 */
class SweepCohortTest {

    private static SignalScoring.Call call(long seconds, String instrument, double directionalReturn) {
        return new SignalScoring.Call(seconds * 1000L, instrument, directionalReturn);
    }

    /**
     * The worked example, and the defect in one assertion.
     *
     * <p>Two passes of a three-name cross-section, each name published a few minutes after the last —
     * the cadence this desk's sensors actually emit at:
     * <pre>
     *   pass 1   t=0s   NQ   +0.0100     t=200s AAPL −0.0060    t=400s AMZN +0.0020
     *   pass 2   t=3600 NQ   −0.0040     t=3800 AAPL +0.0100    t=4000 AMZN +0.0060
     * </pre>
     * Sweep means: {@code (0.0100 − 0.0060 + 0.0020)/3 = 0.0020} and
     * {@code (−0.0040 + 0.0100 + 0.0060)/3 = 0.0040} — TWO draws of the market.
     *
     * <p>Every one of those six calls is more than 60 s from its neighbour, so the superseded gap rule
     * returned SIX cohorts: six independent samples claimed for two passes, and a standard error
     * divided by √6 instead of √2.
     */
    @Test
    void oneSlowPassOverTheCrossSectionIsOneDraw() {
        List<SignalScoring.Call> calls = List.of(
                call(0, "NQ", 0.0100), call(200, "AAPL", -0.0060), call(400, "AMZN", 0.0020),
                call(3600, "NQ", -0.0040), call(3800, "AAPL", 0.0100), call(4000, "AMZN", 0.0060));

        assertThat(SignalScoring.sweepCohortMeans(calls))
                .hasSize(2)
                .satisfies(means -> {
                    assertThat(means.get(0)).isCloseTo(0.0020, within(1e-15));
                    assertThat(means.get(1)).isCloseTo(0.0040, within(1e-15));
                });
    }

    /** A name may appear at most once per cohort — its repeat is what opens the next sweep. */
    @Test
    void aRepeatedNameOpensTheNextSweep() {
        assertThat(SignalScoring.sweepCohortMeans(List.of(
                call(0, "NQ", 0.01), call(1, "AAPL", 0.03),
                call(2, "NQ", 0.05), call(3, "AAPL", 0.07))))
                .hasSize(2)
                .satisfies(means -> {
                    assertThat(means.get(0)).isCloseTo(0.02, within(1e-15));
                    assertThat(means.get(1)).isCloseTo(0.06, within(1e-15));
                });
    }

    /**
     * A source calling ONE name repeatedly gets one cohort per call — those really are independent
     * draws, and the rule must not merge them just because they arrive close together. This is the
     * live shape before the other sensors finish warming: NQ alone, hour after hour.
     */
    @Test
    void aLoneRepeatingNameIsItsOwnDrawEachTime() {
        assertThat(SignalScoring.sweepCohortMeans(List.of(
                call(0, "NQ", 0.01), call(1, "NQ", 0.02), call(2, "NQ", 0.03))))
                .containsExactly(0.01, 0.02, 0.03);
    }

    /**
     * A sensor still warming publishes its first call mid-pass (see the cold-start warnings on the
     * trend/reversion lifecycles). Its occurrence count is 1, which cannot raise the running maximum,
     * so it joins the pass in progress rather than opening a cohort of its own.
     */
    @Test
    void aLateJoiningNameJoinsThePassInProgress() {
        assertThat(SignalScoring.sweepCohortMeans(List.of(
                call(0, "NQ", 0.02), call(10, "AAPL", 0.04),
                call(20, "JNJ", 0.06),           // first call this name has ever made
                call(30, "NQ", 0.08))))          // the repeat — and only now a new sweep
                .hasSize(2)
                .satisfies(means -> {
                    assertThat(means.get(0)).isCloseTo(0.04, within(1e-15));
                    assertThat(means.get(1)).isCloseTo(0.08, within(1e-15));
                });
    }

    /** Grouping is by entry instant, not by the order rows happen to arrive in. */
    @Test
    void groupingIsIndependentOfInputOrder() {
        List<SignalScoring.Call> shuffled = List.of(
                call(3800, "AAPL", 0.0100), call(0, "NQ", 0.0100), call(4000, "AMZN", 0.0060),
                call(400, "AMZN", 0.0020), call(3600, "NQ", -0.0040), call(200, "AAPL", -0.0060));

        assertThat(SignalScoring.sweepCohortMeans(shuffled))
                .hasSize(2)
                .satisfies(means -> {
                    assertThat(means.get(0)).isCloseTo(0.0020, within(1e-15));
                    assertThat(means.get(1)).isCloseTo(0.0040, within(1e-15));
                });
    }

    /**
     * The eight-name pass the live book actually produced on 2026-07-28 collapses to ONE draw, where
     * the gap rule read it as several. The returns are the desk's own resolved observations.
     */
    @Test
    void theLiveEightNamePassIsASingleDraw() {
        List<SignalScoring.Call> pass = List.of(
                call(0, "NQ", -0.01325), call(236, "AAPL", 0.00554), call(491, "AMZN", 0.00513),
                call(506, "MSFT", -0.00077), call(536, "NVDA", -0.01073),
                call(576, "GOOG", -0.00697), call(1601, "JNJ", 0.00048), call(1676, "JPM", 0.00166));

        assertThat(SignalScoring.sweepCohortMeans(pass)).hasSize(1);
    }

    /** No calls is no evidence — never a divisor. */
    @Test
    void noCallsIsNoCohorts() {
        assertThat(SignalScoring.sweepCohortMeans(List.of())).isEmpty();
        assertThat(SignalScoring.sweepCohortMeans(null)).isEmpty();
    }

    /**
     * A nameless call has no sweep identity, so it is dropped rather than placed — placing it would
     * fabricate either independence or merging. Defensive only: the column is {@code not null} and
     * {@link SignalTelemetry#record} rejects a null name.
     */
    @Test
    void aNamelessCallIsDroppedRatherThanPlaced() {
        List<SignalScoring.Call> calls = new java.util.ArrayList<>();
        calls.add(call(0, "NQ", 0.02));
        calls.add(call(1, null, 9.99));   // must not join NQ's sweep, and must not open one
        calls.add(call(2, "NQ", 0.04));
        assertThat(SignalScoring.sweepCohortMeans(calls)).containsExactly(0.02, 0.04);
    }
}
