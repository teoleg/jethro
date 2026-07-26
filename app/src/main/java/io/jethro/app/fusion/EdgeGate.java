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
 *   evidence = resolved_s ≥ minSample  ∧  stdErrorBps_s &gt; 0
 * </pre>
 *
 * <p><b>ADR-0067 — size to the evidence, do not switch on it.</b> ADR-0064 read this arithmetic as a
 * binary go/no-go: increase risk only if some source shows {@code t ≥ tHurdle}, else reduce-only. That
 * rule is unsatisfiable by construction, which is why the desk sat frozen at zero. Note the identity
 * {@code t = ((mean − cost)/σ)·√n}: requiring {@code t ≥ 2} on raw directional returns at horizon is
 * requiring the source to have <i>realised</i> a Sharpe of {@code 2/√n} over the sample window. On this
 * platform's own measured dispersion that bar sits far above any return a real signal produces, so the
 * gate could only ever open on a fluke — and "it re-opens by itself when a source earns its cost" never
 * happens. Worse, it inverted the null: absence of evidence was treated exactly like evidence of harm.
 *
 * <p>So the gate now returns a continuous {@link Decision#riskAppetite() risk appetite} in [0,1]:
 * <pre>
 *   tBest    = max over sources WITH evidence of t_s   (0 when no source has evidence yet)
 *   appetite = Φ(tBest)                                // Φ = standard normal CDF
 *   mayIncrease = tBest &gt; −tHurdle                     // stop only on demonstrated HARM
 * </pre>
 * {@code Φ(tBest)} is the posterior probability, under a diffuse prior, that the best-evidenced
 * source's cost-adjusted expectancy is positive — so the desk bets in proportion to how likely it is
 * to be right (fractional-Kelly sizing; Thorp, "The Kelly Criterion in Blackjack, Sports Betting and
 * the Stock Market", 2006). It carries no dial: with no evidence either way {@code Φ(0) = 1/2} exactly,
 * i.e. half the configured per-name notional. The appetite can only ever <i>shrink</i> the size the
 * configuration already allows — never grow it — so the owner's {@code unit-notional-usd} stays the
 * ceiling. The consumer applies it via {@link FusionPlanner.Params#scaledBy(double)}.
 *
 * <p><b>Reduce-only is kept for the case it was built for.</b> When the best-evidenced source is
 * significantly BELOW its cost ({@code t ≤ −tHurdle}) the desk stops increasing risk entirely — that
 * is measured harm, and ADR-0064's protection is exactly right there. What changes is that an
 * un-measured or merely-unimpressive source no longer triggers the same full stop; it gets a small
 * appetite instead, which is what the evidence actually supports.
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
     * The gate's answer. {@code riskAppetite} ∈ [0,1] scales the per-name notional (ADR-0067);
     * {@code mayIncrease} false ⇒ reduce-only, reserved for demonstrated harm. {@code reason} is prose
     * for the operator; {@code sources} is the per-source arithmetic so the verdict can be recomputed.
     */
    public record Decision(boolean mayIncrease, double riskAppetite, double roundTripCostBps,
                           String reason, List<SourceEdge> sources) {

        /** No source has evidence either way ⇒ tBest = 0 ⇒ Φ(0) = 1/2, the no-evidence appetite. */
        static Decision noEvidence(String reason) {
            return new Decision(true, NO_EVIDENCE_APPETITE, 0.0, reason, List.of());
        }
    }

    /** Φ(0) — the appetite when nothing has been measured either way. Exact by construction, not a dial. */
    static final double NO_EVIDENCE_APPETITE = 0.5;

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
            // and inventing one would be a number without provenance. That is an absence of evidence,
            // so it sizes like one: the no-evidence appetite, not a full stop and not full size.
            return Decision.noEvidence("execution cost not yet measured in this feed mode "
                    + "— sizing at the no-evidence appetite");
        }
        if (stats == null || stats.isEmpty()) {
            return Decision.noEvidence("no signal telemetry yet — sizing at the no-evidence appetite");
        }
        List<SourceEdge> edges = new java.util.ArrayList<>(stats.size());
        double best = Double.NEGATIVE_INFINITY; // NOT 0 — a floor here would hide a measured anti-edge
        boolean anyEvidence = false;
        String bestSource = null;
        for (SignalScoring.Stats s : stats) {
            double net = s.avgReturnBps() - roundTripCostBps;
            double se = s.stdErrorBps();
            // A sample too thin to lean on, or with no dispersion to divide by, is not evidence. It
            // neither raises nor lowers the appetite — it simply does not speak.
            boolean evidence = s.resolved() >= params.minSample() && se > 0;
            double t = evidence ? net / se : 0.0;
            boolean passes = evidence && t >= params.tHurdle();
            edges.add(new SourceEdge(s.source(), s.resolved(), s.avgReturnBps(), net, t, passes));
            if (evidence && t > best) {
                anyEvidence = true;
                best = t;
                bestSource = s.source();
            }
        }
        edges.sort((a, b) -> Double.compare(b.tStat(), a.tStat()));
        double tBest = anyEvidence ? best : 0.0;
        double appetite = standardNormalCdf(tBest);
        boolean mayIncrease = tBest > -params.tHurdle();
        String reason;
        if (!anyEvidence) {
            reason = "no source has the minimum sample yet — sizing at the no-evidence appetite (ADR-0067)";
        } else if (!mayIncrease) {
            reason = String.format(
                    "measured expectancy is significantly BELOW measured execution cost (best source %s, "
                            + "t=%.2f) — reduce-only (ADR-0067)", bestSource, tBest);
        } else {
            reason = String.format(
                    "sized to the measured evidence: best source %s, t=%.2f — risk appetite %.3f (ADR-0067)",
                    bestSource, tBest, appetite);
        }
        return new Decision(mayIncrease, appetite, roundTripCostBps, reason, edges);
    }

    /**
     * Standard normal CDF Φ(x) — Abramowitz &amp; Stegun 26.2.17 (|ε| &lt; 7.5·10⁻⁸). Pure,
     * dimensionless analytics: it converts a t-stat into "probability the edge is positive" and never
     * touches money itself — it becomes a size only where {@link FusionPlanner.Params#scaledBy(double)}
     * multiplies it into an exact decimal notional (the same double-analytics-at-the-boundary
     * convention the impact model uses). Saturates cleanly at the tails, so an extreme t cannot
     * produce anything outside [0,1].
     */
    static double standardNormalCdf(double x) {
        if (Double.isNaN(x)) {
            return NO_EVIDENCE_APPETITE;
        }
        if (x == 0.0) {
            // Φ(0) = 1/2 exactly, by symmetry. The no-evidence appetite is the anchor the whole
            // design rests on, so it is the identity — not the approximation's ~5·10⁻¹⁰ of drift.
            return NO_EVIDENCE_APPETITE;
        }
        if (x < 0) {
            return 1.0 - standardNormalCdf(-x);
        }
        if (x > 40.0) {
            return 1.0; // beyond the approximation's useful range; Φ is 1 to every representable digit
        }
        double t = 1.0 / (1.0 + 0.2316419 * x);
        double poly = t * (0.319381530
                + t * (-0.356563782
                + t * (1.781477937
                + t * (-1.821255978
                + t * 1.330274429))));
        double density = Math.exp(-0.5 * x * x) / Math.sqrt(2.0 * Math.PI);
        double cdf = 1.0 - density * poly;
        return Math.max(0.0, Math.min(1.0, cdf));
    }
}
