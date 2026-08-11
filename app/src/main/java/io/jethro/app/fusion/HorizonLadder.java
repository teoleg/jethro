package io.jethro.app.fusion;

import io.jethro.app.signal.SignalScoring;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The measurement ladder and the choice between its rungs (ADR-0082): over what period is this desk's
 * edge actually real, and therefore how long should it hold a view?
 *
 * <p><b>The question this answers.</b> Expectancy is a return over a period; execution cost is a charge
 * per round trip. The edge gate subtracts one from the other, so the horizon a source is measured over
 * is not a reporting detail — it is half of the comparison that decides whether the desk trades at all.
 * It was a single dial ({@code jethro.signals.horizon-seconds} = 3600 s), chosen years before any of
 * this book's evidence existed, and it silently fixed three things at once: the sample size the gate can
 * ever reach (a cross-sectional source emits ONE independent cohort per horizon, so at an hour the gate
 * accrues evidence at one degree of freedom per hour and stays structurally shut for a working day), the
 * expectancy credited against one round trip, and — since ADR-0080 — the desk's holding period.
 *
 * <p><b>Why the ladder is not a way to buy significance.</b> The tempting move is to shorten the horizon
 * because short rungs resolve faster and therefore accumulate cohorts faster. That reasoning is
 * backwards and would be a fine way to lose money: shrinking the horizon shrinks the expectancy per
 * observation roughly in proportion, while the round trip charged against it does not move at all. A
 * rung only clears if the edge is genuinely FAST — if the move is mostly done inside that period. So the
 * ladder is not a search for a friendlier test, it is a measurement of how quickly this desk's edge
 * decays, and the cost hurdle is what keeps the answer honest. A mean-reverting source may well realise
 * its move in minutes; a trend source will not. The data says which, and it is allowed to say "none".
 *
 * <p><b>And the search is paid for.</b> Reporting the best of {@code m} rungs is {@code m} tests. The
 * multiplicity haircut lives in {@link EdgeGate.Params#alpha()} — Bonferroni, valid under the strong
 * positive dependence between nested measurements of one stream — so every rung, including the base
 * one, faces a strictly HARDER bar than the single-horizon gate did. This change therefore cannot open
 * the gate on evidence the previous gate would have accepted; it can only find an edge at a period the
 * previous gate never looked at, and only on evidence strong enough to survive having looked.
 *
 * <p>Pure and dimensionless: in come measured bps, counts and probabilities, out comes a horizon and a
 * boolean. It sizes nothing and prices nothing (ADR-0016 / invariant 7), and it sits strictly above the
 * deterministic floor.
 */
public final class HorizonLadder {

    private HorizonLadder() {
    }

    /**
     * The span ratio between adjacent rungs. Not a money, risk or exposure number — a measurement
     * geometry, and the same 4× ratio the desk's trend sensor already uses between its fast and slow
     * spans (Carver's standard variations, <i>Systematic Trading</i> 2015). Geometric rather than
     * linear because signal decay is a rate: the interesting question is "an hour or a few minutes?",
     * not "an hour or fifty-eight minutes?".
     */
    public static final int RATIO = 4;

    /**
     * The ladder for a base horizon: {@code base, base/4, base/16, …}, longest first, {@code rungs}
     * long, stopping early rather than emitting a rung shorter than one second or a duplicate.
     *
     * <p>The base is always rung zero, so a one-rung ladder is exactly the pre-ADR-0082 configuration
     * and the base horizon the desk was already measuring over is never dropped.
     */
    public static List<Integer> rungs(int baseSeconds, int rungs) {
        int base = Math.max(1, baseSeconds);
        int count = Math.max(1, rungs);
        List<Integer> out = new ArrayList<>(count);
        int h = base;
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                int next = h / RATIO;
                if (next < 1 || next == h) {
                    break; // the ladder has bottomed out — fewer honest rungs beats duplicated ones
                }
                h = next;
            }
            out.add(h);
        }
        return List.copyOf(out);
    }

    /**
     * The rung the COMBINATION WEIGHTS are estimated over (ADR-0148) — the one whose measurement is
     * best determined, i.e. the most independent cohorts (ADR-0077) summed across sources; ties break
     * toward the longer horizon, as everywhere else in this class.
     *
     * <p><b>Why this is a different question from {@link #select}.</b> The gate asks a COST question —
     * over what period does expectancy beat a round trip — and the answer sets the desk's holding
     * period, so the gate's rung is rightly chosen by cost-adjusted significance. The weights ask a
     * different question, and {@link TelemetryWeights} already says so in the one place it matters:
     * they are computed GROSS of execution cost, because cost decides whether to trade at all and not
     * whose view counts. Whose view counts is a purely DIRECTIONAL question, and the honest rung for a
     * directional question is the one the desk has the most independent draws of.
     *
     * <p><b>This is not the "buy significance by shortening the horizon" error {@link #rungs} warns
     * about.</b> That error is real for the GATE, whose comparison subtracts a round-trip cost that
     * does not shrink with the horizon — so a short rung flatters nothing there and the warning stands
     * unchanged. It does not apply here, because the weighting statistic is {@code Φ(avgReturn/stdError)},
     * which is dimensionless: shortening the horizon shrinks the numerator and the standard error
     * together, so a shorter rung does not mechanically inflate {@code t}. What it does supply is more
     * independent cohorts, and therefore a better-determined {@code t}. That is strictly more evidence
     * about the same directional question, not an easier test of a different one.
     *
     * <p><b>And the criterion is outcome-blind, so no multiplicity haircut is owed.</b> Cohort count is
     * fixed by measurement geometry — how many independent cross-sections a rung resolved in the rolling
     * window — and cannot be moved by the sign or the size of the returns measured. Unlike the gate's
     * search over p-values (which ADR-0082 pays for with Bonferroni), choosing a rung this way peeks at
     * no result, so it cannot manufacture significance. It also cannot scale the book: the combiner
     * normalises by Σweights, so this can only ROTATE conviction between sources.
     *
     * <p>Falls back to {@code selected} when no rung reports a cohort at all — with nothing measured
     * there is no better-determined rung, and the pre-ADR-0148 behaviour stands byte for byte.
     */
    public static List<SignalScoring.Stats> weightingStats(Map<Integer, List<SignalScoring.Stats>> byHorizon,
                                                           List<SignalScoring.Stats> selected) {
        List<SignalScoring.Stats> fallback = selected == null ? List.of() : selected;
        if (byHorizon == null || byHorizon.isEmpty()) {
            return fallback;
        }
        List<SignalScoring.Stats> best = null;
        long bestCohorts = 0;
        long bestHorizon = -1;
        for (var rung : byHorizon.entrySet()) {
            List<SignalScoring.Stats> stats = rung.getValue();
            if (stats == null || stats.isEmpty()) {
                continue;
            }
            long horizon = rung.getKey() == null ? 0L : rung.getKey();
            long cohorts = 0;
            for (SignalScoring.Stats s : stats) {
                // Only a rung that can actually support a standard error counts toward its own
                // evidence: a single cross-section, however wide, is one draw (ADR-0077).
                if (s != null && s.cohorts() > 1) {
                    cohorts += s.cohorts();
                }
            }
            if (cohorts > bestCohorts || (cohorts == bestCohorts && cohorts > 0 && horizon > bestHorizon)) {
                bestCohorts = cohorts;
                bestHorizon = horizon;
                best = stats;
            }
        }
        return best == null ? fallback : List.copyOf(best);
    }

    /**
     * The chosen rung and the gate decision made on it.
     *
     * @param decision  the edge-gate verdict at {@code horizonSeconds} — what the rest of the desk acts on
     * @param stats     that rung's per-source telemetry — the period the gate judged and the desk holds
     * @param rungs     how many rungs were searched — the multiplicity the α was already divided by
     * @param evidenced true when some rung cleared; false means nothing did and the base rung stands
     */
    public record Selection(EdgeGate.Decision decision, List<SignalScoring.Stats> stats, int rungs,
                            long horizonSeconds, boolean evidenced) {

        public Selection {
            stats = stats == null ? List.of() : List.copyOf(stats);
        }
    }

    /**
     * Evaluates the edge gate at every rung and returns the one the evidence picks.
     *
     * <p>Selection rule, in order:
     * <ol>
     *   <li>Among rungs whose gate OPENS, take the smallest best p-value — the strongest evidence, on
     *       the only scale comparable across rungs whose cohort counts (and so degrees of freedom)
     *       differ by more than an order of magnitude.</li>
     *   <li>Ties break toward the LONGER horizon: the same evidence held for longer is fewer round
     *       trips paid for the same credited return, which is strictly cheaper and strictly less
     *       exposed to the desk's own execution.</li>
     *   <li>If no rung opens, the base rung stands. The gate is shut either way, so this only decides
     *       which measurement the operator is shown and which holding period a reduce-only book winds
     *       down at — and the base is the desk's stated default.</li>
     * </ol>
     *
     * @param byHorizon per-rung, per-source telemetry (longest rung first — the base is its first key)
     */
    public static Selection select(Map<Integer, List<SignalScoring.Stats>> byHorizon,
                                   Double roundTripCostBps,
                                   Map<String, Double> roundTripBpsByInstrument,
                                   EdgeGate.Params params) {
        if (byHorizon == null || byHorizon.isEmpty()) {
            return new Selection(EdgeGate.evaluate(List.of(), roundTripCostBps, params), List.of(), 0, 0L, false);
        }
        Selection base = null;
        Selection best = null;
        double bestP = Double.POSITIVE_INFINITY;
        for (var rung : byHorizon.entrySet()) {
            long horizon = rung.getKey() == null ? 0L : rung.getKey();
            List<SignalScoring.Stats> stats = rung.getValue() == null ? List.of() : rung.getValue();
            EdgeGate.Decision decision =
                    EdgeGate.evaluate(stats, roundTripCostBps, roundTripBpsByInstrument, params);
            Selection candidate = new Selection(decision, stats, byHorizon.size(), horizon,
                    decision.mayIncrease() && !decision.sources().isEmpty());
            if (base == null) {
                base = candidate; // the map is ordered longest-first, so the first rung IS the base
            }
            if (!candidate.evidenced()) {
                continue;
            }
            // EdgeGate.evaluate sorts its evidence best-p-value first, so the head is this rung's
            // strongest reading. An open decision always has at least one source behind it.
            double p = decision.sources().get(0).pValue();
            if (p < bestP || (p == bestP && best != null && horizon > best.horizonSeconds())) {
                bestP = p;
                best = candidate;
            }
        }
        return best != null ? best : base;
    }
}
