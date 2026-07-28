package io.jethro.app.fusion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0092 — the measured forecast scalar. The scalar is a dimensionless conviction multiplier, so the
 * assertions here are on doubles; the one test that follows it through to a position size asserts the
 * exact decimal quantity {@link TargetPlanner} produces.
 */
class ForecastScalarsTest {

    private static final int WARM = 4; // small min-sample so the tests state the arithmetic, not the wait

    @Test
    @DisplayName("a source that keeps its promise is untouched — the scalar is exactly 1.0")
    void promiseKeptIsANoOp() {
        var scalars = new ForecastScalars(true, WARM);
        // E|claim| = 10 = TARGET_ABS: readings ±8 and ±12 average to exactly the promise.
        for (double c : new double[] {8, -12, 12, -8, 8, -12}) {
            assertEquals(c, scalars.rescale("trend", c), 1e-12);
        }
        assertEquals(1.0, scalars.scalarFor("trend"), 1e-12);
    }

    @Test
    @DisplayName("a source that under-delivers is NOT levered up — the scalar stays 1.0 (one-way)")
    void underDeliveringSourceIsNeverScaledUp() {
        var scalars = new ForecastScalars(true, WARM);
        for (double c : new double[] {2, -2, 2, -2, 2, -2}) {
            assertEquals(c, scalars.rescale("reversion", c), 1e-12);
        }
        assertEquals(1.0, scalars.scalarFor("reversion"), 1e-12, "E|claim| = 2 < TARGET_ABS — left alone");
    }

    @Test
    @DisplayName("a source running 1.5x its claim is scaled back to TARGET_ABS")
    void overDeliveringSourceIsScaledDown() {
        var scalars = new ForecastScalars(true, WARM);
        // Steady stream at |claim| = 15 → mean 15 → scalar 10/15 = 2/3.
        for (int i = 0; i < WARM; i++) {
            scalars.rescale("trend", i % 2 == 0 ? 15.0 : -15.0);
        }
        assertEquals(2.0 / 3.0, scalars.scalarFor("trend"), 1e-12);
        assertEquals(10.0, scalars.rescale("trend", 15.0), 1e-9, "a typical reading now reads TARGET_ABS");
    }

    @Test
    @DisplayName("below min-sample nothing is rescaled — the un-measured mapper's behaviour exactly")
    void warmUpIsTheOldBehaviour() {
        var scalars = new ForecastScalars(true, 30);
        for (int i = 0; i < 29; i++) {
            assertEquals(40.0, scalars.rescale("trend", 40.0), 1e-12, "reading " + i + " passes through");
        }
        assertEquals(1.0, scalars.scalarFor("trend"), 1e-12);
    }

    @Test
    @DisplayName("disabled is a pass-through and measures nothing")
    void disabledPassesThrough() {
        var scalars = new ForecastScalars(false, WARM);
        for (int i = 0; i < 10; i++) {
            assertEquals(30.0, scalars.rescale("trend", 30.0), 1e-12);
        }
        assertEquals(1.0, scalars.scalarFor("trend"), 1e-12);
        assertTrue(scalars.snapshot().isEmpty(), "a disabled estimator holds no state");
    }

    @Test
    @DisplayName("a no-view (zero / non-finite) claim is not a reading — it neither counts nor rescales")
    void noViewIsNotAReading() {
        var scalars = new ForecastScalars(true, WARM);
        for (int i = 0; i < 20; i++) {
            assertEquals(0.0, scalars.rescale("trend", 0.0), 1e-12);
        }
        assertEquals(0.0, scalars.rescale("trend", Double.NaN), 1e-12);
        assertTrue(scalars.snapshot().isEmpty(), "silence never moved the measured scale");
    }

    @Test
    @DisplayName("sources are measured separately — one saturating source does not shrink another")
    void sourcesAreMeasuredIndependently() {
        var scalars = new ForecastScalars(true, WARM);
        for (int i = 0; i < WARM; i++) {
            scalars.rescale("trend", 20.0);
            scalars.rescale("momentum", 5.0);
        }
        assertEquals(0.5, scalars.scalarFor("trend"), 1e-12);
        assertEquals(1.0, scalars.scalarFor("momentum"), 1e-12);
    }

    @Test
    @DisplayName("the cap stops erasing the cross-section: two clipped names size differently again")
    void declippingRestoresCrossSectionalSelection() {
        var scalars = new ForecastScalars(true, WARM);
        // Warm the estimator at the live measurement: E|claim| = 15 → scalar 2/3.
        for (int i = 0; i < WARM; i++) {
            scalars.rescale("trend", i % 2 == 0 ? 15.0 : -15.0);
        }
        // Two names in the SAME cross-section whose sensors read 1.8 and 2.4 "typical trends" — claims
        // of 18 and 24, both scaled by the one scalar the estimator carries into this cycle.
        double scalar = scalars.scalarFor("trend");
        double weak = Forecast.clamp(18.0 * scalar);
        double strong = Forecast.clamp(24.0 * scalar);
        assertEquals(18.0, Forecast.clamp(18.0), 1e-12, "sanity: unscaled, 18 sits just under the cap");
        assertEquals(20.0, Forecast.clamp(24.0), 1e-12, "sanity: unscaled, 24 is clipped to the cap");

        BigDecimal unit = new BigDecimal("50000");
        BigDecimal price = new BigDecimal("100");
        BigDecimal one = BigDecimal.ONE;
        // Before: 18 → 18 (not clipped) and 24 → 20 (clipped): 900 vs 1000 shares, an 11% gap on a
        // reading 33% stronger. After: 12 and 16 — the full 33% is expressed in the size again.
        assertEquals(new BigDecimal("600.000000"),
                TargetPlanner.targetQuantity(weak, unit, price, one));
        assertEquals(new BigDecimal("800.000000"),
                TargetPlanner.targetQuantity(strong, unit, price, one));
        assertEquals(new BigDecimal("900.000000"),
                TargetPlanner.targetQuantity(18.0, unit, price, one), "the pre-ADR-0092 size");
        assertEquals(new BigDecimal("1000.000000"),
                TargetPlanner.targetQuantity(Forecast.clamp(24.0), unit, price, one),
                "the pre-ADR-0092 size — the cap, not the evidence");
    }

    @Test
    @DisplayName("a genuine extreme still clips — the cap is not being removed, only un-jammed")
    void genuineExtremesStillClip() {
        var scalars = new ForecastScalars(true, WARM);
        for (int i = 0; i < WARM; i++) {
            scalars.rescale("trend", 15.0);
        }
        assertEquals(20.0, Forecast.clamp(scalars.rescale("trend", 45.0)), 1e-9);
    }

    @Test
    @DisplayName("the registry rescales the price sensors and leaves the ordinal sources alone")
    void registryAppliesTheScalarToContinuousSourcesOnly() {
        var params = new ForecastRegistry.Params(3.0, 5.0, 4.0, 20.0);
        var registry = new ForecastRegistry(params, 60_000, new ForecastScalars(true, WARM));
        // Four reversion readings at 2.0 "typical stretches" → claims of 20 → E|claim| = 20 → scalar 0.5.
        for (int i = 0; i < WARM; i++) {
            registry.submitReversion("N" + i, 2.0);
        }
        assertEquals(0.5, registry.scalarSnapshot().get(SourceForecasts.REVERSION).scalar(), 1e-12);
        registry.submitReversion("AAPL", 2.0);
        registry.submitHypothesis("AAPL", io.jethro.domain.Side.BUY,
                io.jethro.trading.algo.hypothesis.Hypothesis.Conviction.HIGH);

        var byInstrument = registry.byInstrument(System.currentTimeMillis());
        double reversion = byInstrument.get("AAPL").stream()
                .filter(f -> f.source().equals(SourceForecasts.REVERSION)).findFirst().orElseThrow().value();
        double hypothesis = byInstrument.get("AAPL").stream()
                .filter(f -> f.source().equals("hypothesis")).findFirst().orElseThrow().value();
        assertEquals(10.0, reversion, 1e-9, "a claim of 20 on a source measured at 20 reads TARGET_ABS");
        assertEquals(15.0, hypothesis, 1e-12, "an ordinal conviction is a category, never rescaled");
        assertEquals(WARM + 1, registry.scalarSnapshot().get(SourceForecasts.REVERSION).readings());
    }

    @Test
    @DisplayName("the claim accessors are the arithmetic the mappers cap — one source of truth")
    void claimsAgreeWithTheMappers() {
        assertEquals(Forecast.clamp(SourceForecasts.trendClaim(1.4, Forecast.TARGET_ABS)),
                SourceForecasts.fromTrend("AAPL", 1.4, Forecast.TARGET_ABS).value(), 1e-12);
        assertEquals(Forecast.clamp(SourceForecasts.reversionClaim(-3.0, Forecast.TARGET_ABS)),
                SourceForecasts.fromReversion("AAPL", -3.0, Forecast.TARGET_ABS).value(), 1e-12);
        assertEquals(Forecast.clamp(SourceForecasts.learnedClaim(0.7, 0.1, true, 20.0)),
                SourceForecasts.fromLearned("AAPL", 0.7, 0.1, true, 20.0).value(), 1e-12);
        assertEquals(0.0, SourceForecasts.trendClaim(Double.NaN, Forecast.TARGET_ABS), 1e-12);
        assertEquals(0.0, SourceForecasts.learnedClaim(0.9, 0.0, false, 20.0), 1e-12);
    }
}
