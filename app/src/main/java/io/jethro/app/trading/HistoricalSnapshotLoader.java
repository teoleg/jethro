package io.jethro.app.trading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jethro.trading.marketdata.sim.HistoricalSnapshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a historical OHLCV snapshot JSON (ADR-0032) into the market-data module's plain
 * {@link HistoricalSnapshot} — JSON stays an app concern so trading-core keeps zero deps, exactly
 * like {@link SimCalibrationLoader}. Shape:
 *
 * <pre>{@code
 * { "source": "yahoo", "asOf": "2026-07-18",
 *   "instruments": ["AAPL", "ES"],
 *   "closes":  { "AAPL": [190.1, ...], "ES": [5450.0, ...] },
 *   "volumes": { "AAPL": [50000000, ...], "ES": [1500000, ...] } }
 * }</pre>
 *
 * All series must share one length (the date axis). Written by the Yahoo history fetch; a bad
 * file fails at startup, never mid-tape.
 */
final class HistoricalSnapshotLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HistoricalSnapshotLoader() {
    }

    static HistoricalSnapshot load(Path path) throws Exception {
        JsonNode root = MAPPER.readTree(Files.readString(path));
        String source = root.path("source").asText("yahoo");
        List<String> ids = new ArrayList<>();
        for (JsonNode n : req(root, "instruments")) {
            ids.add(n.asText());
        }
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("snapshot has no instruments");
        }
        JsonNode closesNode = req(root, "closes");
        JsonNode volumesNode = req(root, "volumes");
        double[][] closes = new double[ids.size()][];
        long[][] volumes = new long[ids.size()][];
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            closes[i] = doubles(req(closesNode, id), "closes." + id);
            volumes[i] = longs(req(volumesNode, id), "volumes." + id);
        }
        return new HistoricalSnapshot(source, ids, closes, volumes); // constructor validates alignment
    }

    private static double[] doubles(JsonNode array, String what) {
        if (!array.isArray() || array.isEmpty()) {
            throw new IllegalArgumentException(what + " must be a non-empty array");
        }
        double[] out = new double[array.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = array.get(i).asDouble();
        }
        return out;
    }

    private static long[] longs(JsonNode array, String what) {
        if (!array.isArray() || array.isEmpty()) {
            throw new IllegalArgumentException(what + " must be a non-empty array");
        }
        long[] out = new long[array.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = array.get(i).asLong();
        }
        return out;
    }

    private static JsonNode req(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            throw new IllegalArgumentException("snapshot is missing required field '" + field + "'");
        }
        return v;
    }
}
