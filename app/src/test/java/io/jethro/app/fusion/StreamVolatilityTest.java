package io.jethro.app.fusion;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** ADR-0086: the mark-stream σ sensor — warm-up, the EWMA, and the √time scaling. */
class StreamVolatilityTest {

    private static final StreamVolatility.Params SPAN_4 = new StreamVolatility.Params(4);

    @Test
    void silentUntilItHasAbsorbedItsWarmUp() {
        var vol = new StreamVolatility(SPAN_4);
        vol.update("ES", new BigDecimal("100.00"));
        for (int i = 0; i < 3; i++) { // 3 returns — one short of the span
            vol.update("ES", new BigDecimal("101.00"));
            vol.update("ES", new BigDecimal("100.00"));
        }
        // 6 returns absorbed, so it IS warm here; check the boundary explicitly instead.
        assertThat(vol.sigmaPerSample("ES")).isPresent();

        var cold = new StreamVolatility(SPAN_4);
        cold.update("ES", new BigDecimal("100.00"));
        cold.update("ES", new BigDecimal("101.00"));
        cold.update("ES", new BigDecimal("100.00"));
        cold.update("ES", new BigDecimal("101.00")); // 3 returns < span 4
        assertThat(cold.sigmaPerSample("ES")).isEmpty();
        cold.update("ES", new BigDecimal("100.00")); // 4th return — now warm
        assertThat(cold.sigmaPerSample("ES")).isPresent();
    }

    @Test
    void anUnknownNameIsNeverMeasured() {
        var vol = new StreamVolatility(SPAN_4);
        assertThat(vol.sigmaPerSample("NOPE")).isEmpty();
        assertThat(vol.sigmaOver("NOPE", 900, 30)).isEmpty();
        assertThat(vol.measuredNames()).isZero();
    }

    @Test
    void warmUpIsARunningMeanOfSquaredLogReturns() {
        // Four alternating 1% steps: r = ±ln(1.01) each, so r² is identical every time and the running
        // mean is exactly r². σ = |ln(1.01)| = 0.00995033...
        var vol = new StreamVolatility(SPAN_4);
        vol.update("X", new BigDecimal("100"));
        vol.update("X", new BigDecimal("101"));
        vol.update("X", new BigDecimal("100"));
        vol.update("X", new BigDecimal("101"));
        vol.update("X", new BigDecimal("100"));
        assertThat(vol.sigmaPerSample("X").getAsDouble())
                .isCloseTo(Math.abs(Math.log(1.01)), within(1e-9));
    }

    @Test
    void sigmaScalesWithTheSquareRootOfTheHorizon() {
        var vol = new StreamVolatility(SPAN_4);
        vol.update("X", new BigDecimal("100"));
        vol.update("X", new BigDecimal("101"));
        vol.update("X", new BigDecimal("100"));
        vol.update("X", new BigDecimal("101"));
        vol.update("X", new BigDecimal("100"));
        double perSample = vol.sigmaPerSample("X").getAsDouble();
        // 900s horizon sampled every 30s = 30 intervals ⇒ ×√30.
        assertThat(vol.sigmaOver("X", 900, 30).getAsDouble())
                .isCloseTo(perSample * Math.sqrt(30.0), within(1e-12));
        // Same period as the sampling interval ⇒ unchanged, exactly.
        assertThat(vol.sigmaOver("X", 30, 30).getAsDouble()).isCloseTo(perSample, within(1e-12));
    }

    @Test
    void aFlatStreamMeasuresNoVolatilityRatherThanZeroSigma() {
        // Zero dispersion is not "this name cannot move" — it is "nothing measured". Reporting σ=0
        // would make any trailing stop fire on the first tick of noise.
        var vol = new StreamVolatility(SPAN_4);
        for (int i = 0; i < 10; i++) {
            vol.update("FLAT", new BigDecimal("100.000000"));
        }
        assertThat(vol.sigmaPerSample("FLAT")).isEmpty();
    }

    @Test
    void absentAndNonPositivePricesAdvanceNothing() {
        var vol = new StreamVolatility(SPAN_4);
        vol.update("X", new BigDecimal("100"));
        vol.update("X", null);
        vol.update("X", BigDecimal.ZERO);
        vol.update("X", new BigDecimal("-5"));
        vol.update(null, new BigDecimal("100"));
        vol.update("X", new BigDecimal("101"));
        vol.update("X", new BigDecimal("100"));
        vol.update("X", new BigDecimal("101"));
        assertThat(vol.sigmaPerSample("X")).isEmpty(); // only 3 real returns absorbed
        vol.update("X", new BigDecimal("100"));
        assertThat(vol.sigmaPerSample("X")).isPresent();
    }

    @Test
    void theEwmaDecaysTowardTheRecentStreamOnceWarm() {
        // Warm on 1% steps, then feed a long run of 0.1% steps: σ must fall toward the quieter regime.
        var vol = new StreamVolatility(SPAN_4);
        vol.update("X", new BigDecimal("100"));
        for (int i = 0; i < 4; i++) {
            vol.update("X", new BigDecimal(i % 2 == 0 ? "101" : "100"));
        }
        double loud = vol.sigmaPerSample("X").getAsDouble();
        for (int i = 0; i < 200; i++) {
            vol.update("X", new BigDecimal(i % 2 == 0 ? "100.100" : "100.000"));
        }
        double quiet = vol.sigmaPerSample("X").getAsDouble();
        assertThat(quiet).isLessThan(loud);
        assertThat(quiet).isCloseTo(Math.abs(Math.log(100.1 / 100.0)), within(1e-6));
    }

    // ---- ADR-0116: a sample is a PRINT, not a cycle -------------------------------------------

    private static Instant at(long seconds) {
        return Instant.ofEpochSecond(1_700_000_000L + seconds);
    }

    /** Warms a sensor on four genuine ±1% prints, each with its own provider timestamp. */
    private static StreamVolatility warmedOnRealPrints() {
        var vol = new StreamVolatility(SPAN_4);
        vol.update("ES", new BigDecimal("100.00"), at(0));
        vol.update("ES", new BigDecimal("101.00"), at(30));
        vol.update("ES", new BigDecimal("100.00"), at(60));
        vol.update("ES", new BigDecimal("101.00"), at(90));
        vol.update("ES", new BigDecimal("100.00"), at(120));
        return vol;
    }

    @Test
    void aRepublishedMarkIsNotAReturn() {
        // The tape stops. The mark cache keeps handing the planner the same price on the same provider
        // clock, once per 30s cycle. None of those is an observation, so σ must be untouched — bit for
        // bit, not approximately: the sensor did not see anything.
        var vol = warmedOnRealPrints();
        double afterRealPrints = vol.sigmaPerSample("ES").getAsDouble();
        assertThat(afterRealPrints).isCloseTo(Math.log(101.0 / 100.0), within(1e-12));

        for (int cycle = 0; cycle < 20; cycle++) {
            vol.update("ES", new BigDecimal("100.00"), at(120)); // the 20:00 close, republished
        }
        assertThat(vol.sigmaPerSample("ES")).hasValue(afterRealPrints);
    }

    @Test
    void withoutTheGateTheSameQuietTapeCollapsesTheCutDistance() {
        // The counterfactual, and the reason this matters: ADR-0086 cuts when the adverse excursion from
        // the peak exceeds k·σ_h, so σ IS the cut distance. Twenty republished marks — ten minutes of a
        // quiet tape at the 30s fusion cadence, nothing like an overnight — decay it by two orders of
        // magnitude, and the desk stops itself out of a position on noise it was sized to sit through.
        var gated = warmedOnRealPrints();
        var ungated = warmedOnRealPrints();
        for (int cycle = 0; cycle < 20; cycle++) {
            gated.update("ES", new BigDecimal("100.00"), at(120));   // no new print — refused
            ungated.update("ES", new BigDecimal("100.00"));          // absorbed as r = ln(1) = 0
        }
        double gatedTrigger = 3.0 * gated.sigmaOver("ES", 3600, 30).getAsDouble();
        double ungatedTrigger = 3.0 * ungated.sigmaOver("ES", 3600, 30).getAsDouble();
        assertThat(ungatedTrigger).isLessThan(gatedTrigger / 100.0);
        // Loose by intent — the claim is the direction and the order of magnitude, not a fixture.
    }

    @Test
    void aGenuineReprintAtTheSamePriceIsStillAnObservation() {
        // The gate is the market's CLOCK, never price equality: a name that really printed 100.00 twice
        // has genuinely not moved, and that zero return is information the sensor must keep.
        var gated = warmedOnRealPrints();
        var reference = warmedOnRealPrints();
        for (int i = 1; i <= 10; i++) {
            gated.update("ES", new BigDecimal("100.00"), at(120 + 30L * i)); // the tape IS printing
            reference.update("ES", new BigDecimal("100.00"));
        }
        assertThat(gated.sigmaPerSample("ES")).hasValue(reference.sigmaPerSample("ES").getAsDouble());
    }

    @Test
    void aNameThatHasOnlyEverBeenRepublishedNeverBecomesMeasured() {
        // The other half of the corruption: the warm-up counter filling with observations that never
        // happened, so the sensor starts speaking a number built out of silence.
        var vol = new StreamVolatility(SPAN_4);
        vol.update("HALTED", new BigDecimal("100.00"), at(0));
        for (int cycle = 0; cycle < 500; cycle++) {
            vol.update("HALTED", new BigDecimal("100.00"), at(0));
        }
        assertThat(vol.sigmaPerSample("HALTED")).isEmpty();
        assertThat(vol.measuredNames()).isZero();
    }

    @Test
    void withNoProviderClockEverySampleIsAdmitted() {
        // A caller with no mark cache (a harness, a replay of the durable series) must behave exactly as
        // it did before the gate existed — declining to measure the clock cannot silence the sensor.
        var withNulls = new StreamVolatility(SPAN_4);
        var without = new StreamVolatility(SPAN_4);
        for (int i = 0; i < 6; i++) {
            BigDecimal p = new BigDecimal(i % 2 == 0 ? "100" : "101");
            withNulls.update("X", p, null);
            without.update("X", p);
        }
        assertThat(withNulls.sigmaPerSample("X")).hasValue(without.sigmaPerSample("X").getAsDouble());
    }
}
