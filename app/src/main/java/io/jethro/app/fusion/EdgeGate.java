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
 * <p><b>The test (ADR-0075).</b> Cost is a property of the name being traded, not of the desk, so the
 * comparison is made at the granularity cost is actually incurred — on <em>both</em> sides. For a source
 * {@code s} over the rolling telemetry window and a name {@code n}:
 * <pre>
 *   cost_n   = measured round trip for n, or the desk's blended round trip when n has never filled
 *   netBps   = avgReturnBps_s − cost_n                // expectancy the desk actually keeps in n
 *   t        = netBps / stdErrorBps_s                 // is that surplus distinguishable from zero?
 *   clears   = resolved_s ≥ minSample  ∧  t ≥ tHurdle
 * </pre>
 * Risk may be increased in {@code n} when ANY source clears there — one demonstrated edge is enough to
 * justify putting on risk, and the per-source combination weights (see {@link TelemetryWeights}) decide
 * how much each one then contributes. The desk-wide verdict is the same question asked of the whole
 * book: the gate is open when some source clears in some name, i.e. against the CHEAPEST round trip the
 * desk can actually pay. Where nothing clears, the gate is REDUCE-ONLY: existing positions may be cut or
 * closed, none may be opened or grown. Note the asymmetry — the gate can only ever <i>subtract</i>
 * trades from what the planner already wanted; it can never add one.
 *
 * <p><b>Why not one blended hurdle (the ADR-0072 lesson, completed).</b> Measured one-way slippage across
 * this book spans two orders of magnitude — a rates future at a fraction of a bp against a wide-spread
 * equity in double digits — so a single blended hurdle is wrong in both directions at once. It
 * under-charges the expensive names, admitting round trips whose cost exceeds the very expectancy that
 * opened the gate; ADR-0072 fixed that half with a per-name veto. It equally over-charges the cheap ones:
 * a source whose edge comfortably survives a 0.3 bp round trip is refused everywhere because the average
 * name costs twenty times that — a real, executable edge suppressed by the cost of names it was never
 * going to trade. Testing each name against its own cost repairs both halves with one rule, and it is
 * strictly TIGHTER than the ADR-0072 veto for any name costing more than the blend (that veto compared a
 * raw mean to a cost; this requires the surplus to clear the significance hurdle).
 *
 * <p>A name the desk has never filled has no measured cost of its own. Inventing one would be a number
 * without provenance (ADR-0016 / invariant 7), so it is charged the desk's own measured blend — which is
 * exactly the bar it faced before this change, leaving unmeasured names' permission unaltered.
 *
 * <p><b>A significance hurdle, not a positive average.</b> Gating on the raw sign of a mean would
 * flip the book on noise: with per-signal dispersion an order of magnitude above the mean, a positive
 * window means nothing. Requiring a t-stat is the standard multiple-testing discipline for exactly
 * this setting (Harvey, Liu &amp; Zhu, "…and the Cross-Section of Expected Returns", <i>RFS</i> 2016;
 * Bailey, Borwein, López de Prado &amp; Zhu, "The Probability of Backtest Overfitting", <i>J. Comp.
 * Finance</i> 2017). The hurdle is a statistical convention, not a money number.
 *
 * <p><b>…read against the right distribution (ADR-0081).</b> The comparison is made in PROBABILITY
 * space, not in units of standard error. The dial {@code tHurdle} states a confidence level the only
 * way it ever could — as a normal quantile — so the gate converts it once, {@code α = 1 − Φ(tHurdle)},
 * and then asks whether the surplus clears that α under the distribution the statistic actually
 * follows: Student's t on {@code cohorts − 1} degrees of freedom, because the ADR-0077 standard error
 * is ESTIMATED from those cohorts rather than known. This is not a refinement at this desk's sample
 * sizes — it is the difference between evidence and none. A cross-sectional source emits ONE cohort per
 * measurement horizon, so B stays single-digit for hours; at B = 3 the true 97.7% one-sided point is
 * ≈ 4.5 standard errors, and a fixed 2.0 there is roughly a 91% test wearing a 97.7% label. Three draws
 * of the market, dressed as proof. The correction is exactly a no-op in the large-sample limit
 * (Student's t → normal as df → ∞), so it can only ever bite where the normal approximation was
 * invalid in the first place, and it bites only in the conservative direction.
 *
 * <p>Pure, dimensionless, exactly testable: in come measured bps and counts, out comes a boolean and
 * a human-readable reason. It sizes nothing and prices nothing (ADR-0016 / invariant 7), and it sits
 * strictly ABOVE the deterministic floor — the pre-trade guardrail and the firm breaker still decide
 * what is safe; this only decides what is worth paying for.
 */
public final class EdgeGate {

    /**
     * Statistical dials — conventions, not market numbers. {@code minSample}: resolved observations
     * before a source's expectancy is allowed to speak at all. {@code tHurdle}: the confidence the desk
     * demands, stated as a normal quantile — how many standard errors of surplus expectancy would count
     * as evidence rather than a lucky window IF the standard error were known exactly.
     */
    public record Params(int minSample, double tHurdle, int hypotheses) {
        public Params {
            if (minSample < 2) {
                minSample = 2; // a standard error needs at least two observations
            }
            if (!(tHurdle > 0)) {
                tHurdle = 2.0;
            }
            if (hypotheses < 1) {
                hypotheses = 1;
            }
        }

        /** A single hypothesis — the pre-ADR-0082 shape, with no multiplicity to correct for. */
        public Params(int minSample, double tHurdle) {
            this(minSample, tHurdle, 1);
        }

        /**
         * The one-sided tail probability the dial asserts (ADR-0081), divided by the number of
         * hypotheses the desk searched to find its best one (ADR-0082).
         *
         * <p>The dial states the confidence the desk demands, so that — not the raw quantile — is what
         * the gate tests against, and the SAME confidence is delivered at every sample size instead of
         * only in the large-sample limit where a normal quantile happens to be right. With one
         * hypothesis the meaning is unchanged: {@code tHurdle} 2.0 is α = 0.02275, exactly as it
         * always was.
         *
         * <p><b>Why divide.</b> Searching {@code m} measurement horizons and reporting the best one
         * is {@code m} tests, not one: under a true null the chance that SOME rung looks significant at
         * α is up to {@code m·α}. Bonferroni's union bound restores the family-wise error rate to α and
         * — unlike Šidák — needs no independence assumption, which matters here because the rungs are
         * nested measurements of the same stream and are strongly positively dependent. This is the
         * haircut the backtest-overfitting literature the gate already cites demands of exactly this
         * procedure (Harvey, Liu &amp; Zhu, <i>RFS</i> 2016; Bailey, Borwein, López de Prado &amp; Zhu,
         * <i>J. Comp. Finance</i> 2017). It can only ever make the gate harder to open.
         */
        public double alpha() {
            return Significance.normalUpperTail(tHurdle) / hypotheses;
        }
    }

    /**
     * One source's reading against the hurdle — the evidence behind {@link Decision}. {@code netEdgeBps}
     * and {@code tStat} are stated at the cheapest round trip the desk can actually pay, which is the
     * statistic the desk-wide verdict turns on; {@link #clears(double, Params)} re-states the same
     * arithmetic at any other name's cost.
     *
     * <p>{@code cohorts} is the INDEPENDENT sample the standard error was estimated from (ADR-0077), and
     * therefore what sets the reference distribution's degrees of freedom; {@code pValue} is the tail
     * probability of {@code tStat} under it (ADR-0081) — the number the verdict actually turns on, and
     * the honest one to show an operator, since a t-stat means nothing without its df.
     */
    public record SourceEdge(String source, long resolved, long cohorts, double avgReturnBps,
                             double stdErrorBps, double netEdgeBps, double tStat, double pValue,
                             boolean passes) {

        /**
         * Does this source's measured expectancy survive {@code roundTripBps} with significance? The
         * surplus is divided by its estimated standard error and the ratio read against Student's t on
         * {@code cohorts − 1} degrees of freedom — the distribution it follows when the denominator is
         * itself an estimate — rather than against a fixed normal quantile (ADR-0081).
         */
        public boolean clears(double roundTripBps, Params params) {
            return EdgeGate.clears(resolved, cohorts, avgReturnBps, stdErrorBps, roundTripBps, params);
        }
    }

    /**
     * The desk's one significance test, in one place: does a measured expectancy of
     * {@code avgReturnBps} — estimated with standard error {@code stdErrorBps} from {@code cohorts}
     * independent draws (ADR-0077) — survive {@code roundTripBps} at the params' confidence? The
     * surplus is divided by its estimated standard error and the ratio read against Student's t on
     * {@code cohorts − 1} degrees of freedom, the distribution it follows when the denominator is
     * itself an estimate, rather than against a fixed normal quantile (ADR-0081).
     *
     * <p>Factored out of {@link SourceEdge#clears} so the WEIGHTING side (ADR-0097) asks the desk's
     * question with the desk's arithmetic instead of a second copy of it that could drift. Passing
     * {@code roundTripBps = 0} asks the pure directional form — "is there an edge at all?" — which is
     * the admission test the weights use, keeping execution cost out of the weighting exactly as
     * {@link TelemetryWeights} intends.
     *
     * <p>Monotone in cost: the test at {@code 0} is implied by the test at any {@code roundTripBps ≥ 0},
     * so nothing can clear the gate that this form rejects.
     */
    public static boolean clears(long resolved, long cohorts, double avgReturnBps, double stdErrorBps,
                                 double roundTripBps, Params params) {
        if (params == null || resolved < params.minSample() || !(stdErrorBps > 0) || cohorts < 2) {
            return false;
        }
        double t = (avgReturnBps - roundTripBps) / stdErrorBps;
        return Significance.studentTUpperTail(t, cohorts - 1.0) <= params.alpha();
    }

    /**
     * Has this source demonstrated a DIRECTIONAL edge — expectancy distinguishable from zero at the
     * desk's own hurdle (ADR-0097)? The admission test for the combination weights; gross of execution
     * cost, because cost decides whether to trade at all ({@link EdgeGate}) and not whose view counts
     * ({@link TelemetryWeights}).
     */
    public static boolean demonstratesEdge(SignalScoring.Stats s, Params params) {
        return s != null
                && clears(s.resolved(), s.cohorts(), s.avgReturnBps(), s.stdErrorBps(), 0.0, params);
    }

    /**
     * The gate's answer. {@code mayIncrease} false ⇒ reduce-only. {@code reason} is prose for the
     * operator; {@code sources} is the per-source arithmetic and {@code roundTripBpsByInstrument} the
     * desk's own measured round-trip cost per name, so every verdict — desk-wide and per name — can be
     * recomputed from the record alone.
     *
     * <p>{@code roundTripCostBps} is the desk's blended measured round trip: the cost charged to a name
     * that has never filled, since no cost of its own has been measured.
     */
    public record Decision(boolean mayIncrease, double roundTripCostBps, String reason,
                           List<SourceEdge> sources,
                           java.util.Map<String, Double> roundTripBpsByInstrument, Params params,
                           long horizonSeconds) {

        public Decision {
            sources = sources == null ? List.of() : List.copyOf(sources);
            roundTripBpsByInstrument = roundTripBpsByInstrument == null
                    ? java.util.Map.of() : java.util.Map.copyOf(roundTripBpsByInstrument);
            params = params == null ? new EdgeGate.Params(2, 2.0) : params;
            if (horizonSeconds < 0) {
                horizonSeconds = 0;
            }
        }

        /** A decision at an unstated horizon — the pre-ADR-0082 shape. */
        public Decision(boolean mayIncrease, double roundTripCostBps, String reason,
                        List<SourceEdge> sources,
                        java.util.Map<String, Double> roundTripBpsByInstrument, Params params) {
            this(mayIncrease, roundTripCostBps, reason, sources, roundTripBpsByInstrument, params, 0L);
        }

        static Decision open(String reason, Params params) {
            return new Decision(true, 0.0, reason, List.of(), java.util.Map.of(), params, 0L);
        }

        /**
         * May the desk put risk ON in this specific name (ADR-0075)? Only when some source's measured
         * expectancy survives <b>this name's own</b> measured round trip with significance — the same
         * test the desk-wide verdict makes, at the granularity cost is actually incurred. A name with no
         * measured cost is charged the desk's blended one rather than an invented one.
         *
         * <p>With no evidence list the gate is inactive (no telemetry, or no fill in this feed mode yet)
         * and there is no per-name test to run: the desk-wide verdict stands alone, exactly as it did
         * before any cost was measured.
         */
        public boolean mayIncrease(String instrument) {
            if (!mayIncrease || sources.isEmpty()) {
                return mayIncrease;
            }
            Double own = instrument == null ? null : roundTripBpsByInstrument.get(instrument);
            double cost = own == null ? roundTripCostBps : own;
            for (SourceEdge e : sources) {
                if (e.clears(cost, params)) {
                    return true;
                }
            }
            return false;
        }
    }

    private EdgeGate() {
    }

    /**
     * @param stats             per-source rolling telemetry (feed-mode scoped by its store, invariant 8)
     * @param roundTripCostBps  the desk's MEASURED round-trip execution cost — the BLEND across every
     *                          name the desk has filled; {@code null} when this feed mode has not
     *                          produced a fill yet, which leaves the gate inactive rather than
     *                          asserting a cost of zero. Per-NAME readings are cleaned before they
     *                          reach this method: a round trip that comes out non-positive is arrival-
     *                          mark drift booked as execution, not price improvement the desk can
     *                          repeat, and {@link QuotedSpreadCost#withQuotedFallback} drops it so the
     *                          name is charged its quote or this blend instead (ADR-0106).
     * @param params            the significance hurdle
     */
    public static Decision evaluate(List<SignalScoring.Stats> stats, Double roundTripCostBps, Params params) {
        return evaluate(stats, roundTripCostBps, java.util.Map.of(), params);
    }

    /**
     * As above, plus the desk's MEASURED round-trip cost per instrument (ADR-0072), used to re-test each
     * name against its own cost once a source has passed. Names absent from the map have not been filled
     * in this feed mode, so no cost is asserted for them and no per-name veto applies.
     *
     * @param roundTripBpsByInstrument measured round-trip slippage in bps, keyed by instrumentId;
     *                                 price-quoted names only (a rate-quoted "bp" is an additive
     *                                 basis point of RATE, not a fraction of notional — blending the
     *                                 two would be a unit error, so the caller excludes them)
     */
    public static Decision evaluate(List<SignalScoring.Stats> stats, Double roundTripCostBps,
                                    java.util.Map<String, Double> roundTripBpsByInstrument, Params params) {
        if (roundTripCostBps == null) {
            // No cost measurement yet (no fills in this mode) — we cannot state a cost-adjusted edge,
            // and inventing one would be a number without provenance. Leave the gate open and let the
            // pre-existing controls (conviction floor, no-trade band, guardrail) stand alone.
            return Decision.open("execution cost not yet measured in this feed mode — gate inactive", params);
        }
        if (stats == null || stats.isEmpty()) {
            return Decision.open("no signal telemetry yet — gate inactive", params);
        }
        java.util.Map<String, Double> costs = roundTripBpsByInstrument == null
                ? java.util.Map.of() : roundTripBpsByInstrument;
        // The cheapest round trip the desk can actually pay (ADR-0075). The desk-wide question — is
        // there ANY edge worth paying for anywhere — is the per-name question at its most favourable
        // name, and the test is monotone in cost, so this one evaluation answers it. The blend is in
        // the running because it is what an as-yet-unfilled name is charged.
        double cheapest = roundTripCostBps;
        for (double c : costs.values()) {
            cheapest = Math.min(cheapest, c);
        }
        List<SourceEdge> edges = new java.util.ArrayList<>(stats.size());
        String passing = null;
        for (SignalScoring.Stats s : stats) {
            double net = s.avgReturnBps() - cheapest;
            double se = s.stdErrorBps();
            double t = se > 0 ? net / se : 0.0;
            long cohorts = s.cohorts();
            // The tail probability of this surplus under the distribution the statistic follows when
            // its denominator is estimated from `cohorts` draws (ADR-0081). A degenerate sample reads
            // p = 1 — no evidence — rather than dividing into an infinite t-stat.
            double p = se > 0 && cohorts >= 2 ? Significance.studentTUpperTail(t, cohorts - 1.0) : 1.0;
            boolean passes = s.resolved() >= params.minSample() && se > 0 && cohorts >= 2
                    && p <= params.alpha();
            edges.add(new SourceEdge(s.source(), s.resolved(), cohorts, s.avgReturnBps(), se, net, t,
                    p, passes));
            if (passes) {
                passing = passing == null ? s.source() : passing + ", " + s.source();
            }
        }
        // Best-evidenced first. Ordering on the p-value rather than the t-stat is the same ordering
        // within one source's history, but the honest one ACROSS sources, whose cohort counts — and so
        // whose degrees of freedom — differ: a t of 2.5 on 3 cohorts is weaker evidence than 2.1 on 40.
        edges.sort((a, b) -> {
            int byP = Double.compare(a.pValue(), b.pValue());
            return byP != 0 ? byP : Double.compare(b.tStat(), a.tStat());
        });
        // Every stat in one evaluation is measured over the same horizon (they are one rung of the
        // ADR-0082 ladder), so the decision can state the period its expectancy — and therefore the
        // holding period the desk owes it — is denominated in. Zero when nothing stated one.
        long horizon = stats.isEmpty() ? 0L : Math.max(0L, stats.get(0).horizonSeconds());
        String at = horizon > 0 ? " (measured over " + horizon + "s)" : "";
        if (passing != null) {
            return new Decision(true, roundTripCostBps,
                    "measured edge clears the cheapest measured round trip with significance" + at + ": "
                            + passing
                            + " — each name is then tested against its own measured cost, and one whose "
                            + "round trip eats that edge stays reduce-only (ADR-0075)",
                    edges, costs, params, horizon);
        }
        return new Decision(false, roundTripCostBps,
                "no source's measured expectancy beats measured execution cost at the demanded confidence "
                        + "in any name" + at + ", read against the degrees of freedom its standard error was "
                        + "estimated from — reduce-only (ADR-0064, ADR-0081)", edges, costs, params, horizon);
    }
}
