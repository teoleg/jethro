package io.jethro.app.fusion;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

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
}
