package io.jethro.app.trading;

import io.jethro.trading.marketdata.sim.FactorModelConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The boot history seed (ADR-0038): stepping the calibrated factor model one trading day per tick
 * produces a deterministic, moving daily-close matrix that seeds the covariance. Deterministic is
 * the point — same seed ⇒ same seeded history, reproducible (invariant 7).
 */
class SimHistorySeederTest {

    private static final List<String> IDS = List.of("AAA", "BBB");
    private static final long[] STARTS = {100_000_000L, 50_000_000L}; // 100.000000, 50.000000

    private static FactorModelConfig cfg() {
        double[][] corr = {
                {1.00, 0.00, 0.00, -0.20},
                {0.00, 1.00, 0.00, 0.00},
                {0.00, 0.00, 1.00, 0.00},
                {-0.20, 0.00, 0.00, 1.00}};
        return new FactorModelConfig(0.18, 0.08, 5.0, 2.0, 6,
                List.of(new FactorModelConfig.InstrumentSpec("AAA", 0.28, 1.30, 0.0),
                        new FactorModelConfig.InstrumentSpec("BBB", 0.20, 0.70, 0.0)),
                List.of(new FactorModelConfig.RegimeSpec("CALM", 0.05, 0, 0, 1.0, corr)),
                new double[][]{{1.0}});
    }

    @Test
    void seedsADeterministicMovingDailyCloseMatrix() {
        long[][] a = SimHistorySeeder.simulateDailyCloses(cfg(), IDS, STARTS, 65, 42);
        long[][] b = SimHistorySeeder.simulateDailyCloses(cfg(), IDS, STARTS, 65, 42);

        assertEquals(65, a.length, "one row per seeded day");
        assertEquals(2, a[0].length, "one close per instrument");
        for (int d = 0; d < a.length; d++) {
            assertArrayEquals(a[d], b[d], "same seed must reproduce the exact seeded history");
        }
        // Prices are positive and actually evolve (not pinned to the start), so the covariance is real.
        boolean moved = false;
        for (int d = 0; d < a.length; d++) {
            assertTrue(a[d][0] > 0 && a[d][1] > 0, "closes stay positive");
            if (a[d][0] != STARTS[0]) {
                moved = true;
            }
        }
        assertTrue(moved, "the seeded tape must move so daily returns aren't all zero");
    }

    @Test
    void aDifferentSeedGivesADifferentTape() {
        long[][] a = SimHistorySeeder.simulateDailyCloses(cfg(), IDS, STARTS, 30, 1);
        long[][] c = SimHistorySeeder.simulateDailyCloses(cfg(), IDS, STARTS, 30, 2);
        boolean differs = false;
        for (int d = 0; d < a.length && !differs; d++) {
            if (a[d][0] != c[d][0]) {
                differs = true;
            }
        }
        assertTrue(differs, "distinct seeds must produce distinct histories");
    }
}
