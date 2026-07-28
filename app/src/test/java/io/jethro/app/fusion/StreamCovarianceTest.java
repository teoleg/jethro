package io.jethro.app.fusion;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0089 — the mark-stream covariance, and the concentration control it feeds.
 *
 * <p>Worked example used throughout (span 2, so the estimator is a plain mean of two joint returns and
 * every assertion below is arithmetic anyone can redo by hand):
 * <pre>
 *   sample 1:  A = 100, B = 100
 *   sample 2:  A = 110, B = 110      rA = rB = ln(1.1)   =  0.0953101798...
 *   sample 3:  A =  99, B =  99      rA = rB = ln(0.9)   = -0.1053605156...
 *
 *   var(A) = var(B) = (ln(1.1)² + ln(0.9)²) / 2 = (0.00908403 + 0.01110084) / 2 = 0.01009244
 *   cov(A,B)                                     = the same, because rA ≡ rB      = 0.01009244
 *   ⇒ correlation = 1 exactly: two names that are one bet.
 * </pre>
 */
class StreamCovarianceTest {

    private static final double LN11 = Math.log(1.1);
    private static final double LN09 = Math.log(0.9);
    private static final double EXPECTED_VAR = (LN11 * LN11 + LN09 * LN09) / 2.0;

    private static Map<String, BigDecimal> sample(Object... pairs) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((String) pairs[i], new BigDecimal(pairs[i + 1].toString()));
        }
        return m;
    }

    @Test
    void measuresTheWorkedExampleExactly() {
        StreamCovariance cov = new StreamCovariance(new StreamCovariance.Params(2));
        cov.update(sample("A", "100", "B", "100"));
        cov.update(sample("A", "110", "B", "110"));
        cov.update(sample("A", "99", "B", "99"));

        assertEquals(EXPECTED_VAR, cov.covariance("A", "A").getAsDouble(), 1e-12);
        assertEquals(EXPECTED_VAR, cov.covariance("B", "B").getAsDouble(), 1e-12);
        assertEquals(EXPECTED_VAR, cov.covariance("A", "B").getAsDouble(), 1e-12);
        // Symmetric by construction — one state per unordered pair.
        assertEquals(cov.covariance("A", "B").getAsDouble(), cov.covariance("B", "A").getAsDouble());
    }

    @Test
    void staysSilentUntilThePairHasAbsorbedItsWarmUp() {
        StreamCovariance cov = new StreamCovariance(new StreamCovariance.Params(3));
        cov.update(sample("A", "100", "B", "100"));
        cov.update(sample("A", "110", "B", "110")); // 1 joint return of a 3-sample warm-up
        assertTrue(cov.covariance("A", "B").isEmpty());
        assertTrue(cov.covariance("A", "A").isEmpty());
        cov.update(sample("A", "99", "B", "99"));
        cov.update(sample("A", "101", "B", "101"));
        assertTrue(cov.covariance("A", "B").isPresent());
    }

    @Test
    void aNameMissingFromASampleContributesNoFabricatedReturn() {
        StreamCovariance withGap = new StreamCovariance(new StreamCovariance.Params(2));
        withGap.update(sample("A", "100", "B", "100"));
        withGap.update(sample("A", "110")); // B did not print — it must not be read as "B was flat"
        withGap.update(sample("A", "99", "B", "99"));
        withGap.update(sample("A", "101", "B", "101"));
        // A has seen 3 returns; B only the two it actually printed, and the pair only those two.
        assertTrue(withGap.covariance("A", "A").isPresent());
        assertTrue(withGap.covariance("B", "B").isPresent());
        // B's own returns are ln(99/100) then ln(101/99) — never ln(1) across the missing sample.
        double rb1 = Math.log(0.99);
        double rb2 = Math.log(101.0 / 99.0);
        assertEquals((rb1 * rb1 + rb2 * rb2) / 2.0, withGap.covariance("B", "B").getAsDouble(), 1e-12);
    }

    @Test
    void anUnseenNameIsNotCovered() {
        StreamCovariance cov = new StreamCovariance(new StreamCovariance.Params(2));
        cov.update(sample("A", "100"));
        cov.update(sample("A", "110"));
        cov.update(sample("A", "99"));
        assertTrue(cov.covariance("A", "ZZZ").isEmpty());
        assertTrue(cov.covariance("ZZZ", "ZZZ").isEmpty());
        assertFalse(cov.seen("ZZZ"));
        assertEquals(1, cov.measuredNames(List.of("A", "ZZZ")));
    }

    @Test
    void aNameThatNeverMovesIsAWarmUpStatementNotARiskStatement() {
        StreamCovariance cov = new StreamCovariance(new StreamCovariance.Params(2));
        cov.update(sample("A", "100"));
        cov.update(sample("A", "100"));
        cov.update(sample("A", "100"));
        assertTrue(cov.covariance("A", "A").isEmpty(), "a zero variance would divide the vol budget by 0");
    }

    /**
     * The point of the whole change: two perfectly correlated names sized as if independent are scaled
     * back to ONE budget of risk. With equal notionals e and the worked example's Σ:
     * <pre>
     *   σ_indep² = e²(v + v)          = 2 e² v
     *   σ_actual² = e²(v + v + 2v)    = 4 e² v      (ρ = 1)
     *   PDM = √(2/4) = 1/√2 = 0.70710678…           = 1/√N for N = 2, exactly as ADR-0079 states
     * </pre>
     */
    @Test
    void feedsTheConcentrationControlItWasBuiltFor() {
        StreamCovariance cov = new StreamCovariance(new StreamCovariance.Params(2));
        cov.update(sample("A", "100", "B", "100"));
        cov.update(sample("A", "110", "B", "110"));
        cov.update(sample("A", "99", "B", "99"));

        double pdm = PortfolioRiskNormaliser.multiplier(List.of("A", "B"), List.of(50_000.0, 50_000.0),
                cov.asSource());
        assertEquals(1.0 / Math.sqrt(2.0), pdm, 1e-9);
    }

    /**
     * The units property the wiring rests on: the estimator measures a per-SAMPLE variance where the
     * daily-close estimate measures a per-DAY one, and the control is homogeneous of degree zero in Σ,
     * so the sampling period cannot move a size. Scaling every entry by 390 (a trading day of minutes)
     * leaves the multiplier unchanged — exactly in the arithmetic, to the last ulp in doubles.
     */
    @Test
    void theMultiplierIsInvariantToTheSamplingPeriod() {
        StreamCovariance cov = new StreamCovariance(new StreamCovariance.Params(2));
        cov.update(sample("A", "100", "B", "101"));
        cov.update(sample("A", "110", "B", "104"));
        cov.update(sample("A", "99", "B", "106"));

        ReturnCovarianceSource perSample = cov.asSource();
        ReturnCovarianceSource perDay = (a, b) -> {
            var v = perSample.covariance(a, b);
            return v.isEmpty() ? v : java.util.OptionalDouble.of(v.getAsDouble() * 390.0);
        };
        List<String> names = List.of("A", "B");
        List<Double> notionals = List.of(50_000.0, 30_000.0);
        assertEquals(PortfolioRiskNormaliser.multiplier(names, notionals, perSample),
                PortfolioRiskNormaliser.multiplier(names, notionals, perDay), 1e-12);
    }

    /**
     * ADR-0117 — the same off-by-one, one dimension up. At span 2 the estimator needs two JOINT
     * returns, and the three-snapshot replay of the worked example above is what produces them: two
     * snapshots give one return and leave every pair cold simultaneously, which is the ADR-0089
     * concentration control silent on the whole book.
     */
    @Test
    void theJointSeedNeedsOneMoreSnapshotThanTheEstimatorCountsReturns() {
        StreamCovariance shortOne = new StreamCovariance(new StreamCovariance.Params(2));
        assertEquals(3, shortOne.warmupSnapshots());
        assertEquals(shortOne.warmupSamples() + 1, shortOne.warmupSnapshots());

        shortOne.update(sample("A", "100", "B", "100"));
        shortOne.update(sample("A", "110", "B", "110")); // 2 snapshots → 1 joint return
        assertTrue(shortOne.covariance("A", "B").isEmpty());
        assertEquals(0, shortOne.measuredNames(List.of("A", "B")));

        shortOne.update(sample("A", "99", "B", "99")); // the 3rd snapshot completes the 2nd return
        assertTrue(shortOne.covariance("A", "B").isPresent());
        assertEquals(2, shortOne.measuredNames(List.of("A", "B")));
    }

    /** One-way by construction: an internally hedged book is never levered UP on an estimated ρ. */
    @Test
    void anAntiCorrelatedBookIsNeverLeveredUp() {
        StreamCovariance cov = new StreamCovariance(new StreamCovariance.Params(2));
        cov.update(sample("A", "100", "B", "100"));
        cov.update(sample("A", "110", "B", "90"));
        cov.update(sample("A", "99", "B", "99"));

        double pdm = PortfolioRiskNormaliser.multiplier(List.of("A", "B"), List.of(50_000.0, 50_000.0),
                cov.asSource());
        assertEquals(1.0, pdm, 1e-12);
    }
}
