package io.jethro.app.training;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the ADR-0053 training matrix on demand: for every instrument on file it runs the pure
 * {@link FeatureBuilder} over that instrument's real daily bars and reports what the model layer would
 * actually get to learn from — how many labelled rows exist and, crucially, how they are BALANCED
 * across UP/DOWN/FLAT. Class balance is the first thing that kills a naive classifier (a set that is
 * 90% FLAT trains a model that always says FLAT and scores well), so we surface it before any model is
 * built. Read-only analytics — never a number into live sizing/risk (ADR-0016 / invariant 7).
 */
public final class FeatureService {

    private final TrainingBarsStore store;
    private final double thresholdReturn;

    public FeatureService(TrainingBarsStore store, double labelThresholdBps) {
        this.store = store;
        this.thresholdReturn = Math.abs(labelThresholdBps) / 10_000.0; // bps → fraction
    }

    /** Per-instrument label counts, so the training set's usefulness is visible before any model. */
    public record LabelCounts(String instrument, int up, int down, int flat) {
        public int total() {
            return up + down + flat;
        }
    }

    public record Summary(double labelThresholdBps, int instruments, int totalRows,
                          int up, int down, int flat, List<LabelCounts> perInstrument) {
    }

    /** Rebuilds every instrument's feature rows and tallies label balance — the training-set health check. */
    public Summary summary() {
        List<LabelCounts> per = new ArrayList<>();
        Map<FeatureBuilder.Label, Integer> tot = new EnumMap<>(FeatureBuilder.Label.class);
        for (FeatureBuilder.Label l : FeatureBuilder.Label.values()) {
            tot.put(l, 0);
        }
        for (String id : store.instruments()) {
            var counts = new EnumMap<FeatureBuilder.Label, Integer>(FeatureBuilder.Label.class);
            for (FeatureBuilder.Label l : FeatureBuilder.Label.values()) {
                counts.put(l, 0);
            }
            for (FeatureBuilder.FeatureRow row : FeatureBuilder.build(id, store.series(id), thresholdReturn)) {
                counts.merge(row.label(), 1, Integer::sum);
                tot.merge(row.label(), 1, Integer::sum);
            }
            int up = counts.get(FeatureBuilder.Label.UP);
            int down = counts.get(FeatureBuilder.Label.DOWN);
            int flat = counts.get(FeatureBuilder.Label.FLAT);
            if (up + down + flat > 0) {
                per.add(new LabelCounts(id, up, down, flat));
            }
        }
        int up = tot.get(FeatureBuilder.Label.UP);
        int down = tot.get(FeatureBuilder.Label.DOWN);
        int flat = tot.get(FeatureBuilder.Label.FLAT);
        return new Summary(thresholdReturn * 10_000.0, per.size(), up + down + flat, up, down, flat, per);
    }

    /** Every instrument's labelled rows pooled into one time-orderable set — the backtest's input. */
    public List<FeatureBuilder.FeatureRow> allRows() {
        List<FeatureBuilder.FeatureRow> out = new ArrayList<>();
        for (String id : store.instruments()) {
            out.addAll(FeatureBuilder.build(id, store.series(id), thresholdReturn));
        }
        return out;
    }

    /** The most recent {@code limit} feature rows for one instrument — a spot-check the maths is sane. */
    public List<FeatureBuilder.FeatureRow> sample(String instrument, int limit) {
        List<FeatureBuilder.FeatureRow> rows = FeatureBuilder.build(instrument, store.series(instrument), thresholdReturn);
        int from = Math.max(0, rows.size() - Math.max(1, limit));
        return new ArrayList<>(rows.subList(from, rows.size()));
    }
}
