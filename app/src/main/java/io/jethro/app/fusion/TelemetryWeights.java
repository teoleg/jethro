package io.jethro.app.fusion;

import io.jethro.app.signal.SignalScoring;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns ADR-0055 phase-1 per-signal telemetry into per-source combination weights (closing ADR-0055
 * item 6 → item 2): a source is trusted in proportion to its MEASURED directional edge, shrunk toward
 * the pooled average so a thin sample can't dominate and a cold start reads equal. This replaces the
 * phase-4 equal-weight placeholder — a decayed source is "down-weighted by measurement, not decree."
 *
 * <p>Method (Grinold–Kahn advantage + Bühlmann credibility shrinkage + Carver-style bounding):
 * <pre>
 *   advantage_s = max(0, 2·hitRate_s − 1)          // directional skill in [0,1]; a coin-flip/worse ⇒ 0.
 *                                                   // Floored, never inverted (contrarian = overfitting).
 *   n_s         = wins_s + losses_s                 // decisive obs (hitRate's own denominator; FLATs = no bet)
 *   c_s         = n_s / (n_s + K)                   // credibility: trust the data as the sample grows
 *   pool        = mean_s(advantage_s)              // the pooled prior every source shrinks toward
 *   shrunk_s    = c_s·advantage_s + (1−c_s)·pool   // thin sources ≈ pool; rich sources ≈ their own edge
 *   w_s         = clamp(shrunk_s / mean_s(shrunk_s), MIN, MAX)   // centre on 1.0 (equal = the null)
 * </pre>
 * Cold start (no decisive obs): c=0 ⇒ every shrunk = pool ⇒ every weight = 1.0 (equal). No measured
 * edge anywhere (all advantages 0): mean is 0 ⇒ fall back to equal. {@link ForecastCombiner} normalises
 * by Σweights, so only the RATIOS matter; MIN&gt;0 keeps a decayed source CONTRIBUTING (down-weighted,
 * not dropped — the combiner skips weight≤0). Pure, dimensionless, exactly testable — a conviction
 * weight, never a size or a price (ADR-0016 / invariant 7).
 */
public final class TelemetryWeights {

    /**
     * Modelling dials — MINE, to validate against OOS, NOT market conventions. shrinkageK: the number of
     * decisive observations at which a source's data is half-trusted vs the pooled prior (higher ⇒ more
     * shrinkage toward equal). min/max bound each weight around the 1.0 null so no source is silenced or
     * dominates on thin evidence (the analogue of Carver's diversification-multiplier cap).
     */
    public record Params(double shrinkageK, double min, double max) {
        public Params {
            shrinkageK = shrinkageK > 0 ? shrinkageK : 20.0;
            if (min <= 0) {
                min = 0.25;
            }
            if (max < min) {
                max = Math.max(min, 3.0);
            }
        }
    }

    private TelemetryWeights() {
    }

    /** Per-source weights from telemetry stats. Empty in → empty out (caller falls back to the equal default). */
    public static Map<String, Double> compute(List<SignalScoring.Stats> stats, Params p) {
        Map<String, Double> out = new HashMap<>();
        if (stats == null || stats.isEmpty()) {
            return out;
        }
        Map<String, Double> advantage = new HashMap<>();
        Map<String, Double> credibility = new HashMap<>();
        double poolSum = 0;
        for (SignalScoring.Stats s : stats) {
            double adv = Math.max(0.0, 2.0 * s.hitRate() - 1.0);
            double nDecisive = s.wins() + s.losses();
            advantage.put(s.source(), adv);
            credibility.put(s.source(), nDecisive / (nDecisive + p.shrinkageK()));
            poolSum += adv;
        }
        double pool = poolSum / advantage.size();

        Map<String, Double> shrunk = new HashMap<>();
        double shrunkSum = 0;
        for (var e : advantage.entrySet()) {
            double c = credibility.get(e.getKey());
            double v = c * e.getValue() + (1 - c) * pool;
            shrunk.put(e.getKey(), v);
            shrunkSum += v;
        }
        double meanShrunk = shrunkSum / shrunk.size();
        if (!(meanShrunk > 0)) {
            // No source shows any measured edge yet → honest equal weighting.
            for (String src : shrunk.keySet()) {
                out.put(src, 1.0);
            }
            return out;
        }
        for (var e : shrunk.entrySet()) {
            out.put(e.getKey(), clamp(e.getValue() / meanShrunk, p.min(), p.max()));
        }
        return out;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
