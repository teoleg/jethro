package io.jethro.app.fusion;

import io.jethro.app.signal.SignalScoring;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns ADR-0055 phase-1 per-signal telemetry into per-source combination weights (closing ADR-0055
 * item 6 → item 2): a source is trusted in proportion to its MEASURED directional edge, shrunk toward
 * the other sources' average so a thin sample can't dominate and a cold start reads equal. This
 * replaces the phase-4 equal-weight placeholder — a decayed source is "down-weighted by measurement,
 * not decree."
 *
 * <p><b>Trust is measured on EXPECTANCY, not on hit rate</b> (ADR-0067). What makes a source worth
 * listening to is the money its calls make per observation, not how often it is merely on the right
 * side. Hit rate is only the sign-based proxy for Grinold–Kahn's IC, and that proxy is valid solely
 * when payoffs are symmetric; a trend source is *designed* to be right under half the time and paid by
 * asymmetry, so ranking it on hit rate reads its intended shape as failure.
 *
 * <p><b>Weight is proportional to the EDGE, not to the confidence that the edge is positive</b>
 * (ADR-0093). The statistic is the source's own measured mean return, shrunk toward the evidence from
 * the rest of the desk:
 * <pre>
 *   µ̂_s      = avgReturnBps_s                    // this source's measured expectancy, gross of cost
 *   n_s      = resolved_s                        // the SAME observations µ̂_s averaged over
 *   c_s      = n_s / (n_s + K)                   // credibility: trust the data as the sample grows
 *   pool_s   = mean_{r≠s}(µ̂_r)                   // LEAVE-ONE-OUT prior — the rest of the desk
 *   ê_s      = c_s·µ̂_s + (1−c_s)·pool_s          // Bühlmann posterior mean of this source's edge
 *   w_raw_s  = max(0, ê_s)                       // down-weighted to a whisper, never inverted
 *   w_s      = clamp(w_raw_s / mean_s(w_raw_s), MIN, MAX)
 * </pre>
 *
 * <p><b>Why the edge and not Φ(t).</b> The previous statistic was {@code Φ(t_s)} — the evidence that a
 * source's true expectancy is <em>positive</em>. That is a confidence, and combining forecasts in
 * proportion to confidence has two defects that were both binding on the live desk. First, <b>Φ puts
 * the null at ½, and the normaliser turns ½ into a full unit of weight</b>: a source measuring
 * {@code +0.28 bps} against a {@code ±8.0 bps} standard error — literally no information — carried
 * 49% of the weight of a source measured at {@code t = +9.0} over 483 resolved calls. A source that
 * has shown nothing must be worth nothing, not half. Second, <b>confidence saturates and edge does
 * not</b>: {@code Φ(2.0) = 0.977} against {@code Φ(9.4) = 1.000}, so an overwhelming edge was allowed
 * at most 2.3% more weight than one that barely cleared a 2-sigma hurdle — all discrimination among
 * the good sources was squeezed out at the top of the scale, exactly where it pays. Between them the
 * four sources that failed the desk's own significance test carried 59% of the weight mass against the
 * one that passed it. Proportionality to expected return is the textbook combination rule (Grinold &amp;
 * Kahn, <i>Active Portfolio Management</i>, ch. 11 — weight ∝ IC·volatility) and it has the right two
 * limits: no measured edge ⇒ no weight, and twice the edge ⇒ twice the weight, without a ceiling that
 * arrives at t = 2.
 *
 * <p><b>Still bounded below at zero: a measured-bad source is DOWN-weighted, never inverted</b> into a
 * contrarian bet on its own failure (inverting a losing signal is the canonical overfit; Harvey, Liu
 * &amp; Zhu, <i>RFS</i> 2016). {@code MIN > 0} keeps it CONTRIBUTING rather than dropped (the combiner
 * skips weight ≤ 0), so the active-source count — and with it the ADR-0076 diversification multiplier —
 * is unchanged.
 *
 * <p><b>The prior is LEAVE-ONE-OUT.</b> A source is shrunk toward the mean of the <em>other</em>
 * sources' readings, never toward a pool containing itself. On the bounded Φ scale self-inclusion was
 * harmless because everything saturated; on the unbounded edge scale it is not — a thin source with an
 * extreme reading drags the pool toward itself and is then shrunk toward its own luck, which is exactly
 * backwards. Leaving it out is the standard empirical-Bayes construction and restores the intended
 * behaviour: a spectacular reading on three calls lands <em>below</em> neutral, not above it.
 *
 * <p><b>Credibility counts the sample the estimate was made from (ADR-0074).</b> {@code n_s} is the
 * resolved count, because {@code µ̂_s} averages over EVERY resolved observation — FLATs included. A
 * FLAT is not "no bet" to an expectancy: it is a call that earned nothing, and it lowers the mean
 * exactly as it should. Counting only wins+losses would measure the confidence of one statistic with
 * the sample size of a different one, and a source whose calls mostly land inside the flat dead-band —
 * a property of its horizon and threshold, not of how much evidence it has — would be permanently
 * treated as thin however long it ran. Hit rate still excludes FLATs (there, "no bet" is the right
 * reading); credibility does not.
 *
 * <p><b>Shrinkage is the whole thin-sample defence (ADR-0074).</b> There is deliberately no hard
 * minimum-sample floor pinning a source at exactly 1.0. Bühlmann credibility already does that job,
 * continuously and in the right direction: at K = 20 a source with three resolved calls sits ~87% on
 * the prior no matter how spectacular its reading, so a lucky run cannot up-weight it.
 *
 * <p><b>When no source has a positive expected edge, weights are equal</b> — there is no basis on
 * which to prefer one view of a book that is being wound down, and it is the ADR-0064 edge gate, not a
 * weight, that stops the desk adding risk in that state: no source passes its significance test, so
 * every name is reduce-only regardless of how the remaining conviction is shaped. This is a narrower
 * degenerate case than the one ADR-0067 was written to remove, which collapsed whenever any source
 * merely fell below a coin flip — with profitable sources still on the desk.
 *
 * <p><b>Scale-free.</b> Multiply every source's measured expectancy by any k &gt; 0 and every ê, the
 * mean, and therefore every weight is unchanged — so the unit the telemetry reports in cannot move a
 * weight. Expectancy is measured GROSS of execution cost here on purpose: cost decides *whether the
 * desk should pay to trade at all*, which is {@link EdgeGate}'s job (ADR-0064), while these weights
 * decide only *whose view counts more* among sources that all face the same cost. Keeping the two
 * separate stops one measurement from being charged twice.
 *
 * <p>{@link ForecastCombiner} normalises by Σweights, so only the RATIOS matter — this can rotate
 * conviction between sources but can never scale the target book up or down. Pure, dimensionless,
 * exactly testable — a conviction weight, never a size or a price (ADR-0016 / invariant 7).
 */
public final class TelemetryWeights {

    /**
     * Modelling dials — MINE, to validate against OOS, NOT market conventions. shrinkageK: the number of
     * resolved observations at which a source's data is half-trusted vs the leave-one-out prior (higher
     * ⇒ more shrinkage toward the rest of the desk). min/max bound each weight around the 1.0 null so no
     * source is silenced or dominates on thin evidence (the analogue of Carver's diversification-
     * multiplier cap).
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
        Map<String, Double> edge = new HashMap<>();
        Map<String, Double> credibility = new HashMap<>();
        double edgeSum = 0;
        for (SignalScoring.Stats s : stats) {
            // The measured edge itself, in bps per resolved call — magnitude, not confidence. A source
            // with no resolved observation reports 0.0, which is the honest "nothing measured yet".
            double mu = s.avgReturnBps();
            // Credibility is the confidence in THAT statistic, so it counts the observations THAT
            // statistic averaged over — every resolved call, FLATs included (ADR-0074).
            long n = s.resolved();
            edge.put(s.source(), mu);
            credibility.put(s.source(), n / (n + p.shrinkageK()));
            edgeSum += mu;
        }
        int sources = edge.size();

        Map<String, Double> shrunk = new HashMap<>();
        double shrunkSum = 0;
        for (var e : edge.entrySet()) {
            // LEAVE-ONE-OUT prior: the rest of the desk's average reading, never a pool this source is
            // itself inside — otherwise a thin extreme reading is shrunk toward its own luck.
            double pool = sources > 1 ? (edgeSum - e.getValue()) / (sources - 1) : e.getValue();
            double c = credibility.get(e.getKey());
            // A measured-bad source is down-weighted to the MIN floor, never inverted into a
            // contrarian bet: the positive part is taken before any ratio is formed.
            double v = Math.max(0.0, c * e.getValue() + (1 - c) * pool);
            shrunk.put(e.getKey(), v);
            shrunkSum += v;
        }
        double meanShrunk = shrunkSum / sources;
        if (!(meanShrunk > 0)) {
            // Cold start, or a desk on which no source has a positive expected edge. There is no ratio
            // to form and no basis to prefer one view: weight equally and let the ADR-0064 edge gate —
            // which no source passes in this state — hold every name reduce-only.
            for (String src : shrunk.keySet()) {
                out.put(src, 1.0);
            }
            return out;
        }
        for (var e : shrunk.entrySet()) {
            // No hard min-sample floor (ADR-0074): the credibility term above already holds a thin
            // source next to the prior — continuously, and symmetrically for good and bad readings
            // alike — so a lucky run cannot up-weight it and a measured loss is not read as "no
            // evidence". Everyone gets the shrunk weight, bounded.
            out.put(e.getKey(), clamp(e.getValue() / meanShrunk, p.min(), p.max()));
        }
        return out;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * Standard normal CDF Φ(x) — Abramowitz &amp; Stegun 26.2.17 (Zelen &amp; Severo), |error| &lt; 7.5e-8.
     * A statistical convention, not a market number. It no longer sets a source's weight (ADR-0093 —
     * weight is proportional to the measured edge, not to the confidence that the edge is positive);
     * it survives here because {@link Significance} converts the edge gate's t-hurdle into the
     * confidence that hurdle always claimed (ADR-0081). Package-private so the mapping itself is
     * unit-testable against published values.
     */
    static double standardNormalCdf(double x) {
        if (Double.isNaN(x)) {
            return 0.5; // no statistic ⇒ no evidence
        }
        if (x > 40.0) {
            return 1.0;
        }
        if (x < -40.0) {
            return 0.0;
        }
        final double p = 0.2316419;
        final double b1 = 0.319381530;
        final double b2 = -0.356563782;
        final double b3 = 1.781477937;
        final double b4 = -1.821255978;
        final double b5 = 1.330274429;
        double ax = Math.abs(x);
        double t = 1.0 / (1.0 + p * ax);
        double poly = t * (b1 + t * (b2 + t * (b3 + t * (b4 + t * b5))));
        double density = Math.exp(-0.5 * ax * ax) / Math.sqrt(2.0 * Math.PI);
        double upperTail = density * poly; // ≈ 1 − Φ(|x|)
        return x >= 0 ? 1.0 - upperTail : upperTail;
    }
}
