package io.jethro.trading.marketdata.sim;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The historical snapshot holder (ADR-0032): shape validation, provenance labelling, and the
 *  cross-sectional log-return matrix the bootstrap resamples. */
class HistoricalSnapshotTest {

    @Test
    void syntheticSeedIsLabelledAndWellFormed() {
        var snap = HistoricalSnapshot.synthetic(1, List.of("AAPL", "ES"),
                new double[]{190, 5450}, new double[]{0.28, 0.16},
                new long[]{50_000_000L, 1_500_000L}, 300);
        assertTrue(snap.synthetic(), "the generated seed must declare itself synthetic, not real");
        assertEquals(300, snap.days());
        assertEquals(2, snap.instrumentIds().size());
        assertTrue(snap.lastClose(0) > 0);
        assertEquals(299, snap.logReturns()[0].length, "returns are days-1 long");
    }

    @Test
    void rejectsNonPositiveCloseAndMisalignedSeries() {
        assertThrows(IllegalArgumentException.class, () -> new HistoricalSnapshot("yahoo",
                List.of("AAPL"), new double[][]{{100, 0, 101}}, new long[][]{{1, 1, 1}}));
        assertThrows(IllegalArgumentException.class, () -> new HistoricalSnapshot("yahoo",
                List.of("AAPL", "ES"), new double[][]{{100, 101}}, new long[][]{{1, 1}}));
    }

    @Test
    void logReturnsAreCrossSectionallyAligned() {
        // Two instruments, hand-built: same-day column must be the same calendar day for both.
        var snap = new HistoricalSnapshot("yahoo", List.of("A", "B"),
                new double[][]{{100, 110, 121}, {50, 55, 60.5}},
                new long[][]{{10, 20, 30}, {1, 2, 3}});
        double[][] r = snap.logReturns();
        assertEquals(Math.log(110.0 / 100.0), r[0][0], 1e-12);
        assertEquals(Math.log(55.0 / 50.0), r[1][0], 1e-12);
        assertEquals(2, r[0].length);
    }
}
