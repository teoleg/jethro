package io.jethro.app.trading;

import io.jethro.trading.marketdata.sim.HistoricalSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Capture aligns instruments on the intersection of trading days and writes a snapshot the
 *  loader round-trips (ADR-0032). Offline via a fake history source. */
class SnapshotCaptureTest {

    private static HistoryClient.History hist(long[] days, double[] closes, long[] vols) {
        return new HistoryClient.History(days, closes, vols);
    }

    @Test
    void alignsOnCommonDaysAndRoundTripsThroughTheLoader(@TempDir Path dir) throws Exception {
        Map<String, HistoryClient.History> data = Map.of(
                "AAPL_Y", hist(new long[]{1, 2, 3}, new double[]{100, 101, 102}, new long[]{10, 20, 30}),
                "ES_Y", hist(new long[]{2, 3, 4}, new double[]{50, 51, 52}, new long[]{1, 2, 3}));
        var capture = new SnapshotCapture(sym -> Optional.ofNullable(data.get(sym)), "tiingo");

        Map<String, String> ids = new LinkedHashMap<>();
        ids.put("AAPL", "AAPL_Y");
        ids.put("ES", "ES_Y");
        Path out = dir.resolve("history.json");

        SnapshotCapture.Result result = capture.capture(ids, out);
        assertEquals(2, result.instruments());
        assertEquals(2, result.days(), "only days 2 and 3 are common to both instruments");

        HistoricalSnapshot snap = HistoricalSnapshotLoader.load(out);
        assertEquals(List.of("AAPL", "ES"), snap.instrumentIds());
        assertEquals(2, snap.days());
        assertEquals(101.0, snap.close(0, 0), 1e-9); // AAPL, day 2
        assertEquals(102.0, snap.close(0, 1), 1e-9); // AAPL, day 3
        assertEquals(50.0, snap.close(1, 0), 1e-9);  // ES, day 2
        assertEquals(2L, snap.volume(1, 1));         // ES, day 3 volume
    }

    @Test
    void failsWhenNoInstrumentHasHistory(@TempDir Path dir) {
        var capture = new SnapshotCapture(sym -> Optional.empty(), "tiingo");
        Map<String, String> ids = Map.of("AAPL", "AAPL_Y");
        assertThrows(Exception.class, () -> capture.capture(ids, dir.resolve("x.json")));
    }
}
