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
 * <p><b>Trust is measured on EXPECTANCY, not on hit rate</b> (ADR-0067). What makes a source worth
 * listening to is the money its calls make per observation relative to the noise in them — its realised
 * information ratio — not how often it is merely on the right side. Hit rate is only the sign-based
 * proxy for Grinold–Kahn's IC, and that proxy is valid solely when payoffs are symmetric; a trend source
 * is *designed* to be right under half the time and paid by asymmetry, so ranking it on hit rate reads
 * its intended shape as failure. The statistic below is each source's own measured mean return against
 * its own standard error — magnitude, dispersion and sample size in one number.
 *
 * <p>Method (measured expectancy + Bühlmann credibility shrinkage + Carver-style bounding):
 * <pre>
 *   t_s         = avgReturnBps_s / stdErrorBps_s   // sample t of expectancy vs zero (0 ⇒ no dispersion yet)
 *   e_s         = Φ(t_s) ∈ (0,1)                   // evidence the true expectancy is positive; Φ(0)=½ exactly
 *   n_s         = resolved_s                        // the SAME observations e_s was estimated from
 *   c_s         = n_s / (n_s + K)                   // credibility: trust the data as the sample grows
 *   pool        = mean_s(e_s)                       // the pooled prior every source shrinks toward
 *   shrunk_s    = c_s·e_s + (1−c_s)·pool            // thin sources ≈ pool; rich sources ≈ their own edge
 *   w_s         = clamp(shrunk_s / mean_s(shrunk_s), MIN, MAX)   // centre on 1.0 (equal = the null)
 * </pre>
 * Cold start (no resolved obs ⇒ no standard error): every e = Φ(0) = ½ ⇒ every shrunk = pool ⇒ every
 * weight = 1.0. Equal by symmetry, not by a fallback branch.
 *
 * <p><b>Credibility counts the sample the estimate was made from (ADR-0074).</b> {@code n_s} is the
 * resolved count, because {@code e_s} is a function of {@code avgReturnBps} and {@code stdErrorBps},
 * and both of those average over EVERY resolved observation — FLATs included. A FLAT is not "no bet"
 * to an expectancy: it is a call that earned nothing, and it lowers the mean and the standard error
 * exactly as it should. Counting only wins+losses in the credibility term therefore measured the
 * confidence of one statistic with the sample size of a different one, and the discrepancy is not
 * small: a source whose calls mostly land inside the flat dead-band — a property of its horizon and
 * threshold, not of how much evidence it has — was permanently treated as thin however long it ran.
 * Two sources with byte-identical measured expectancy and identical resolved samples could differ
 * more than threefold in conviction purely on how their outcomes bucketed. Hit rate still excludes
 * FLATs (there, "no bet" is the right reading); credibility does not.
 *
 * <p><b>Shrinkage is the whole thin-sample defence (ADR-0074).</b> There is deliberately no hard
 * minimum-sample floor pinning a source at exactly 1.0. Bühlmann credibility already does that job,
 * continuously and in the right direction: at K = 20 a source with three resolved calls sits ~87% on
 * the pooled prior no matter how spectacular its reading, so a lucky run cannot up-weight it. A hard
 * floor on top was a second, discontinuous copy of the same protection — and not a neutral one, since
 * clamping to 1.0 is strictly MORE trusting than the shrunk value for every below-average source. It
 * read measured-negative evidence as no evidence, and put a cliff in the weight function at n = the
 * threshold.
 *
 * <p><b>Why Φ and not the raw t.</b> Φ is bounded in (0,1) and monotone, so a weight can never be
 * negative — a measured-bad source is DOWN-weighted toward MIN, never inverted into a contrarian bet
 * (inverting a losing signal is the canonical overfit; Harvey, Liu &amp; Zhu, <i>RFS</i> 2016). It also
 * keeps the null at exactly ½ for every source regardless of sample, so "no evidence" reads equal.
 * Crucially the previous statistic, {@code max(0, 2·hitRate−1)}, was floored at zero, which made every
 * below-coin-flip source identical: when the whole desk is losing — precisely when discrimination is
 * worth most — it collapsed to the degenerate all-zero case and weighted a measured loser exactly like
 * everyone else. Φ has no such flat region.
 *
 * <p>Expectancy is measured GROSS of execution cost here on purpose: cost decides *whether the desk
 * should pay to trade at all*, which is {@link EdgeGate}'s job (ADR-0064), while these weights decide
 * only *whose view counts more* among sources that all face the same cost. Keeping the two separate
 * stops one measurement from being charged twice.
 *
 * <p><b>…but Φ saturates, so it needs an admission test (ADR-0097).</b> Φ is a probability, and
 * essentially its whole dynamic range is {@code t ∈ [−2.5, 2.5]}: past the desk's hurdle the curve is
 * flat, so a source that barely clears reads almost identically to one that clears by a factor of six.
 * That is right for the question Φ answers and wrong for the one the combiner asks. So a source that has
 * not demonstrated a directional edge — {@link EdgeGate#demonstratesEdge}, the desk's own significance
 * test at zero cost — is held at MIN by {@link #compute(List, Params, EdgeGate.Params)}. Strictly
 * one-way (MIN is the clamp bound already applied), and inert unless some source IS admitted.
 *
 * <p>{@link ForecastCombiner} normalises by Σweights, so only the RATIOS matter — this can rotate
 * conviction between sources but can never scale the target book up or down; MIN&gt;0 keeps a decayed
 * source CONTRIBUTING (down-weighted, not dropped — the combiner skips weight≤0) so the active-source
 * count, and with it the diversification multiplier, is unchanged. Pure, dimensionless, exactly testable
 * — a conviction weight, never a size or a price (ADR-0016 / invariant 7).
 */
public final class TelemetryWeights {

    /**
     * Modelling dials — MINE, to validate against OOS, NOT market conventions. shrinkageK: the number of
     * resolved observations at which a source's data is half-trusted vs the pooled prior (higher ⇒ more
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
        return compute(stats, p, null);
    }

    /**
     * The same weights, with the ADR-0097 admission rule applied: a source that has NOT demonstrated a
     * directional edge at the desk's own significance hurdle is held at {@code p.min()} — it still
     * contributes (the combiner skips only weight ≤ 0, so the active-source count and with it the
     * diversification multiplier are unchanged) but it may not out-vote a source that has.
     *
     * <p><b>Why the continuous statistic alone is not enough.</b> The evidence term is {@code Φ(t)}, a
     * PROBABILITY, and Φ saturates: essentially its whole dynamic range lies in {@code t ∈ [−2.5, 2.5]},
     * so above the hurdle the function is flat and a source that barely clears is indistinguishable from
     * one that clears by a factor of six. That is correct for the question Φ answers — "how sure are we
     * the sign is positive?" — and wrong for the question the combiner asks, which is whose view should
     * steer the book. Measured live, a source at {@code t = 1.69} (failing the desk's own gate) carried
     * ~83% of the weight of the only source clearing it at {@code t = 10.6}, and on the desk's largest
     * position it out-voted that source by an order of magnitude and reversed the sign of the trade.
     *
     * <p><b>The test is the desk's own, at zero cost.</b> {@link EdgeGate#demonstratesEdge} — expectancy
     * against its Fama–MacBeth standard error (ADR-0077), read against Student's t on {@code cohorts−1}
     * at the gate's own α (ADR-0081/0082). No new dial and no new statistic: the same two dials the gate
     * already uses. Gross of execution cost deliberately, so no measurement is charged twice.
     *
     * <p><b>One-way, and inert when it has nothing to defer to.</b> {@code p.min()} is the lower clamp
     * bound this method already applies, so a demotion can only ever LOWER a weight — a failing source is
     * never inverted into a contrarian bet and no weight is ever raised. And the rule applies only when
     * at least one source IS admitted: with nothing to defer to, demoting everyone would merely flatten
     * the weight vector, and in that state the gate is reduce-only anyway (its test is the same one at a
     * non-negative cost, so nothing can clear it that fails here) — so the desk is adding no risk.
     *
     * @param admission the edge gate's own {@code minSample}/α parameters; {@code null} disables the rule
     *                  and returns the pre-ADR-0097 weights byte for byte
     */
    public static Map<String, Double> compute(List<SignalScoring.Stats> stats, Params p,
                                              EdgeGate.Params admission) {
        Map<String, Double> out = weights(stats, p);
        if (admission == null || out.isEmpty()) {
            return out;
        }
        List<SignalScoring.Stats> demoted = new java.util.ArrayList<>(stats.size());
        boolean anyAdmitted = false;
        for (SignalScoring.Stats s : stats) {
            if (EdgeGate.demonstratesEdge(s, admission)) {
                anyAdmitted = true;
            } else {
                demoted.add(s);
            }
        }
        if (!anyAdmitted) {
            return out; // nothing has earned the right to steer — leave the weights as measured
        }
        for (SignalScoring.Stats s : demoted) {
            out.computeIfPresent(s.source(), (k, w) -> Math.min(w, p.min()));
        }
        return out;
    }

    private static Map<String, Double> weights(List<SignalScoring.Stats> stats, Params p) {
        Map<String, Double> out = new HashMap<>();
        if (stats == null || stats.isEmpty()) {
            return out;
        }
        Map<String, Double> evidence = new HashMap<>();
        Map<String, Double> credibility = new HashMap<>();
        double poolSum = 0;
        for (SignalScoring.Stats s : stats) {
            // Evidence that this source's true expectancy is positive: Φ of its own sample t-statistic.
            // No standard error yet (≤1 observation, or no dispersion) ⇒ t=0 ⇒ Φ(0)=½, i.e. no evidence.
            double se = s.stdErrorBps();
            double t = se > 0 ? s.avgReturnBps() / se : 0.0;
            double ev = standardNormalCdf(t);
            // Credibility is the confidence in THAT statistic, so it counts the observations THAT
            // statistic averaged over — every resolved call, FLATs included (ADR-0074).
            long n = s.resolved();
            evidence.put(s.source(), ev);
            credibility.put(s.source(), n / (n + p.shrinkageK()));
            poolSum += ev;
        }
        double pool = poolSum / evidence.size();

        Map<String, Double> shrunk = new HashMap<>();
        double shrunkSum = 0;
        for (var e : evidence.entrySet()) {
            double c = credibility.get(e.getKey());
            double v = c * e.getValue() + (1 - c) * pool;
            shrunk.put(e.getKey(), v);
            shrunkSum += v;
        }
        double meanShrunk = shrunkSum / shrunk.size();
        if (!(meanShrunk > 0)) {
            // Unreachable while Φ > 0, but a defensive floor: if every source's evidence underflowed
            // to zero there is no ratio to form → honest equal weighting.
            for (String src : shrunk.keySet()) {
                out.put(src, 1.0);
            }
            return out;
        }
        for (var e : shrunk.entrySet()) {
            // No hard min-sample floor (ADR-0074): the credibility term above already holds a thin
            // source next to the pooled prior — continuously, and symmetrically for good and bad
            // readings alike — so a lucky run cannot up-weight it and a measured loss is not read as
            // "no evidence". Everyone gets the shrunk weight, bounded.
            out.put(e.getKey(), clamp(e.getValue() / meanShrunk, p.min(), p.max()));
        }
        return out;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * Standard normal CDF Φ(x) — Abramowitz &amp; Stegun 26.2.17 (Zelen &amp; Severo), |error| &lt; 7.5e-8.
     * A statistical convention, not a market number: it maps a dimensionless t-statistic onto the
     * bounded evidence scale (0,1), with Φ(0) = ½ exactly by construction. Package-private so the
     * mapping itself is unit-testable against published values.
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
