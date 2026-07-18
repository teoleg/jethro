package io.jethro.trading.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Live volume statistics (ADR-0032): the smoothed average converges to the traded notional,
 *  the relative-volume ratio flags bursts, and an unseen instrument reads as neutral. */
class VolumeStatsTest {

    private static final long P100 = 100_000_000L; // price 100.0 scaled 1e6
    private static final long Q10 = 10_000_000L;   // qty 10 scaled 1e6  → notional 1000

    @Test
    void averageConvergesToTradedNotional() {
        var stats = new VolumeStats();
        for (int i = 0; i < 20_000; i++) {
            stats.record("ES", P100, Q10);
        }
        assertEquals(1000.0, stats.avgTradeNotional("ES"), 1.0);
        assertEquals(20_000, stats.sampleCount("ES"));
        assertEquals(1.0, stats.relativeVolume("ES"), 0.02, "steady flow ⇒ rel-vol ≈ 1");
    }

    @Test
    void relativeVolumeRisesOnABurstAndFallsWhenThin() {
        var stats = new VolumeStats();
        for (int i = 0; i < 10_000; i++) {
            stats.record("ES", P100, Q10); // baseline notional 1000
        }
        for (int i = 0; i < 100; i++) {
            stats.record("ES", P100, Q10 * 5); // a burst: 5× the size
        }
        assertTrue(stats.relativeVolume("ES") > 1.3,
                "a sustained 5× burst must lift rel-vol above baseline: " + stats.relativeVolume("ES"));

        var thin = new VolumeStats();
        for (int i = 0; i < 10_000; i++) {
            thin.record("AAPL", P100, Q10);
        }
        for (int i = 0; i < 100; i++) {
            thin.record("AAPL", P100, Q10 / 5); // dries up
        }
        assertTrue(thin.relativeVolume("AAPL") < 0.8,
                "thinning volume must drop rel-vol below baseline: " + thin.relativeVolume("AAPL"));
    }

    @Test
    void unseenInstrumentIsNeutral() {
        var stats = new VolumeStats();
        assertEquals(0.0, stats.avgTradeNotional("NOPE"));
        assertEquals(1.0, stats.relativeVolume("NOPE"));
        assertEquals(0, stats.sampleCount("NOPE"));
    }
}
