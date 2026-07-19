package io.jethro.app.trading;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Captures a historical OHLCV snapshot (ADR-0032): fetches each instrument's daily series, aligns
 * them on the <b>intersection</b> of trading days (so the cross-section the bootstrap resamples is
 * genuinely same-day across instruments), and writes the JSON {@link HistoricalSnapshotLoader}
 * reads. The fetch source is injected so it's testable offline; production passes a
 * {@link YahooHistoryClient}. External symbols are used only to query — the snapshot keys on the
 * internal {@code instrumentId} (invariant 2).
 */
public final class SnapshotCapture {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Fetches one symbol's history; empty when the source has nothing usable. */
    public interface HistorySource {
        Optional<YahooHistoryClient.History> fetch(String yahooSymbol);
    }

    public record Result(String path, int instruments, int days, String source) {
    }

    private final HistorySource source;

    public SnapshotCapture(HistorySource source) {
        this.source = source;
    }

    /** Fetches {@code instrumentId → yahooSymbol}, aligns, and writes the snapshot to {@code out}. */
    public Result capture(Map<String, String> instrumentToYahoo, Path out) throws IOException {
        // Per instrument: epoch-day → [close, volume], sorted by date.
        Map<String, TreeMap<Long, double[]>> byId = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : instrumentToYahoo.entrySet()) {
            Optional<YahooHistoryClient.History> h = source.fetch(e.getValue());
            if (h.isEmpty()) {
                continue; // no usable history for this name — leave it out of the snapshot
            }
            YahooHistoryClient.History hist = h.get();
            TreeMap<Long, double[]> series = new TreeMap<>();
            for (int i = 0; i < hist.epochDays().length; i++) {
                series.put(hist.epochDays()[i], new double[]{hist.closes()[i], hist.volumes()[i]});
            }
            byId.put(e.getKey(), series);
        }
        if (byId.isEmpty()) {
            throw new IOException("no instrument returned usable history");
        }

        TreeSet<Long> dates = null;
        for (TreeMap<Long, double[]> series : byId.values()) {
            if (dates == null) {
                dates = new TreeSet<>(series.keySet());
            } else {
                dates.retainAll(series.keySet());
            }
        }
        if (dates == null || dates.size() < 2) {
            throw new IOException("fewer than 2 common trading days across the fetched instruments");
        }
        List<Long> axis = new ArrayList<>(dates);

        ObjectNode root = MAPPER.createObjectNode();
        root.put("source", "yahoo");
        root.put("asOf", LocalDate.now().toString());
        ArrayNode ids = root.putArray("instruments");
        ObjectNode closes = root.putObject("closes");
        ObjectNode volumes = root.putObject("volumes");
        for (Map.Entry<String, TreeMap<Long, double[]>> entry : byId.entrySet()) {
            ids.add(entry.getKey());
            ArrayNode ca = closes.putArray(entry.getKey());
            ArrayNode va = volumes.putArray(entry.getKey());
            for (long d : axis) {
                double[] cv = entry.getValue().get(d);
                ca.add(cv[0]);
                va.add((long) cv[1]);
            }
        }

        Path abs = out.toAbsolutePath();
        if (abs.getParent() != null) {
            Files.createDirectories(abs.getParent());
        }
        Files.writeString(abs, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        return new Result(abs.toString(), byId.size(), axis.size(), "yahoo");
    }
}
