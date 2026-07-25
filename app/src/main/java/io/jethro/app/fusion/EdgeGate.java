package io.jethro.app.fusion;

import io.jethro.app.signal.SignalScoring;

import java.util.List;

/**
 * The ADR-0064 cost-aware edge gate: may the fusion layer INCREASE risk right now, or may it only
 * reduce it? Answered from measurement alone — the sources' own realised expectancy against the
 * desk's own realised execution cost — never from a view.
 *
 * <p><b>Why this exists.</b> A target-portfolio loop rebalances on a cadence whether or not the
 * forecast driving it has any edge. Turnover is a certain cost; edge is an uncertain benefit. When
 * expectancy is indistinguishable from zero, every round trip has negative expected value, and by
 * Grinold's Fundamental Law (IR ≈ IC·√breadth, <i>JPM</i> 1989) spreading a zero-or-negative IC across
 * breadth scales the <i>loss</i>. So "trade less" — not "trade differently" — is the response the
 * evidence supports. This is the live-edge gate ADR-0062 asked for, applied at the sole order origin.
 *
 * <p><b>The test.</b> For each source, over the rolling telemetry window:
 * <pre>
 *   netBps_s = avgReturnBps_s − roundTripCostBps      // expectancy the desk actually keeps
 *   t_s      = netBps_s / stdErrorBps_s               // is that surplus distinguishable from zero?
 *   passes_s = resolved_s ≥ minSample  ∧  t_s ≥ tHurdle
 * </pre>
 * Risk may be increased when ANY source passes — one demonstrated edge is enough to justify putting
 * on risk, and the per-source combination weights (see {@link TelemetryWeights}) decide how much each
 * one then contributes. When none passes, the gate is REDUCE-ONLY: existing positions may be cut or
 * closed, none may be opened or grown. Note the asymmetry — the gate can only ever <i>subtract</i>
 * trades from what the planner already wanted; it can never add one.
 *
 * <p><b>A significance hurdle, not a positive average.</b> Gating on the raw sign of a mean would
 * flip the book on noise: with per-signal dispersion an order of magnitude above the mean, a positive
 * window means nothing. Requiring a t-stat is the standard multiple-testing discipline for exactly
 * this setting (Harvey, Liu &amp; Zhu, "…and the Cross-Section of Expected Returns", <i>RFS</i> 2016;
 * Bailey, Borwein, López de Prado &amp; Zhu, "The Probability of Backtest Overfitting", <i>J. Comp.
 * Finance</i> 2017). The hurdle is a statistical convention, not a money number.
 *
 * <p>Pure, dimensionless, exactly testable: in come measured bps and counts, out comes a boolean and
 * a human-readable reason. It sizes nothing and prices nothing (ADR-0016 / invariant 7), and it sits
 * strictly ABOVE the deterministic floor — the pre-trade guardrail and the firm breaker still decide
 * what is safe; this only decides what is worth paying for.
 */
public final class EdgeGate {

    /**
     * Statistical dials — conventions, not market numbers. {@code minSample}: resolved observations
     * before a source's expectancy is allowed to speak at all. {@code tHurdle}: how many standard
     * errors of surplus expectancy count as evidence rather than a lucky window.
     */
    public record Params(int minSample, double tHurdle) {
        public Params {
            if (minSample < 2) {
                minSample = 2; // a standard error needs at least two observations
            }
            if (!(tHurdle > 0)) {
                tHurdle = 2.0;
            }
        }
    }

    /** One source's reading against the hurdle — the evidence behind {@link Decision}. */
    public record SourceEdge(String source, long resolved, double avgReturnBps, double netEdgeBps,
                             double tStat, boolean passes) {
    }

    /**
     * The gate's answer. {@code mayIncrease} false ⇒ reduce-only. {@code reason} is prose for the
     * operator; {@code sources} is the per-source arithmetic so the verdict can be recomputed.
     */
    public record Decision(boolean mayIncrease, double roundTripCostBps, String reason,
                           List<SourceEdge> sources) {

        static Decision open(String reason) {
            return new Decision(true, 0.0, reason, List.of());
        }
    }

    private EdgeGate() {
    }

    /**
     * @param stats             per-source rolling telemetry (feed-mode scoped by its store, invariant 8)
     * @param roundTripCostBps  the desk's MEASURED round-trip execution cost; {@code null} when this
     *                          feed mode has not produced a fill yet. Null and "zero" are deliberately
     *                          distinct — a measured zero (or a negative, i.e. price improvement) is a
     *                          real reading the gate must use, not an absent one.
     * @param params            the significance hurdle
     */
    public static Decision evaluate(List<SignalScoring.Stats> stats, Double roundTripCostBps, Params params) {
        if (roundTripCostBps == null) {
            // No cost measurement yet (no fills in this mode) — we cannot state a cost-adjusted edge,
            // and inventing one would be a number without provenance. Leave the gate open and let the
            // pre-existing controls (conviction floor, no-trade band, guardrail) stand alone.
            return Decision.open("execution cost not yet measured in this feed mode — gate inactive");
        }
        if (stats == null || stats.isEmpty()) {
            return Decision.open("no signal telemetry yet — gate inactive");
        }
        List<SourceEdge> edges = new java.util.ArrayList<>(stats.size());
        boolean anyPasses = false;
        String passing = null;
        for (SignalScoring.Stats s : stats) {
            double net = s.avgReturnBps() - roundTripCostBps;
            double se = s.stdErrorBps();
            double t = se > 0 ? net / se : 0.0;
            boolean passes = s.resolved() >= params.minSample() && se > 0 && t >= params.tHurdle();
            edges.add(new SourceEdge(s.source(), s.resolved(), s.avgReturnBps(), net, t, passes));
            if (passes) {
                anyPasses = true;
                passing = passing == null ? s.source() : passing + ", " + s.source();
            }
        }
        edges.sort((a, b) -> Double.compare(b.tStat(), a.tStat()));
        if (anyPasses) {
            return new Decision(true, roundTripCostBps,
                    "measured edge clears cost with significance: " + passing, edges);
        }
        return new Decision(false, roundTripCostBps,
                "no source's measured expectancy beats measured execution cost with significance "
                        + "— reduce-only (ADR-0064)", edges);
    }
}
