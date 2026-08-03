package io.jethro.app.fusion;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * The fusion desk's risk-reactive exit (ADR-0086): cut a position whose price has retraced from its own
 * best level by more than the name's own measured volatility says a live view should ever retrace.
 * A volatility-scaled trailing stop — the chandelier exit — expressed in the desk's own units.
 *
 * <h2>Why the desk needs one</h2>
 * The fusion layer is a pure target-portfolio loop: a position exists exactly as long as the combined
 * forecast asks for it, and it is closed only when that forecast decays. Nothing anywhere in that path
 * asks "is this position <em>losing</em>?" — the ADR-0064 edge gate asks whether risk may be put ON, the
 * ADR-0059 conviction floor whether a view is strong enough to act on, the ADR-0080 rate how fast to get
 * there. The one control that reacts to a position going wrong is the FIRM drawdown breaker, which is a
 * whole-book backstop, not a per-name one. The legacy strategy path has had a per-position stop since
 * ADR-0019 ({@code jethro.strategy.stop-loss-pct}); the fusion path — the desk with the measured edge and
 * the sole order origin — has had none.
 *
 * <p>That gap is most expensive for exactly the source the evidence currently favours. A mean-reversion
 * view is short optionality: it is right most of the time for a little and wrong rarely for a lot, so its
 * unmanaged payoff is a long run of small gains ended by one large loss. Cutting the tail is not a
 * refinement of such a book, it is the thing that makes its expectancy survivable — and it is the owner's
 * stated thesis in one line: let winners run, cut losers fast.
 *
 * <h2>The rule</h2>
 * For a name the desk holds, with mark {@code m} and the best mark {@code P} seen since the position was
 * opened (the running maximum for a long, the running minimum for a short):
 * <pre>
 *   e   = (P − m)/P     long          adverse excursion from the peak, a fraction ≥ 0
 *   e   = (m − P)/P     short
 *   σ_h = the name's measured σ over the desk's own holding horizon   (StreamVolatility, ADR-0086)
 *   CUT when  e > k · σ_h
 * </pre>
 * A cut sets that name's target to FLAT, which the ADR-0080 delta trades in full in one cycle, and holds
 * the name reduce-only for one holding horizon before it may be re-entered. Both periods are the horizon
 * the EVIDENCE selected (ADR-0082) — not a dial: the position was put on to earn one horizon's return, so
 * that is the period over which an adverse move is evidence the view is wrong, and the period the desk
 * must stand aside for before paying to express it again.
 *
 * <h2>Why measured from the PEAK and not from entry</h2>
 * Measuring from entry is a stop-loss: it never protects a profit, so a position that runs 3σ in the
 * desk's favour and then gives all of it back is never cut. Measuring from the running peak is the
 * chandelier exit (LeBeau; Kaufman, <i>Trading Systems and Methods</i>, ch. 23), and it is the one that
 * implements "let winners run": while the position is winning, the trigger price follows it up and the
 * risk taken never grows; the moment it turns, the same fixed distance applies. At entry the peak IS the
 * entry, so a trade that never works is cut on the ordinary stop-loss distance — this rule is strictly
 * tighter than an entry stop, never looser.
 *
 * <h2>What it cannot do</h2>
 * It can only ever set a target to zero and clamp a delta to a reduction: {@link #apply} never raises a
 * target, never flips a sign, and never lets a name trade in the direction that grows exposure. So it
 * cannot lever the book up on the strength of an estimated σ, and a name whose σ is not measured yet is
 * left exactly as planned — with no measurement there is no claim to make, and inventing a volatility
 * would be a risk number without provenance (ADR-0016 / invariant 7). It sits strictly ABOVE the
 * deterministic floor: the pre-trade guardrail and the firm breaker are untouched.
 *
 * <p>Exact decimal on every price and quantity (invariant 1); σ enters as a dimensionless statistic at
 * the same boundary {@link ReturnCovarianceSource} draws. Not thread-safe: confined to the fusion tick.
 */
public final class TrailingRiskCut {

    private static final int EXCURSION_SCALE = 12;

    /**
     * The one dial: how many horizon-σ of adverse excursion from the peak count as evidence the view is
     * wrong.
     *
     * <p><b>{@code sigmaMultiple} = 3.0 is a PLACEHOLDER — Oleg to set.</b> It is not derived from
     * anything in this desk's measurements; it is the upper end of the conventional chandelier-exit
     * distance (2.5–3 × ATR, LeBeau), read here against a horizon σ rather than an ATR. Three was chosen
     * as the CONSERVATIVE end of that convention on purpose: for a cut rule, "conservative" means
     * reluctant to cut, because a stop set inside the ordinary noise of the holding period converts the
     * strategy's routine winners into realised losses and destroys the very expectancy it is protecting.
     * The desk's edge over the same horizon is roughly 0.08% against a horizon σ near 0.09%, so 3σ places
     * the trigger at several times the move the position was opened to capture.
     */
    public record Params(double sigmaMultiple) {
        public Params {
            if (!(sigmaMultiple > 0)) {
                sigmaMultiple = 3.0;
            }
        }
    }

    /** One firing of the rule — everything needed to recompute the verdict from the record alone. */
    public record Cut(String instrument, int side, BigDecimal peak, BigDecimal mark,
                      double excursion, double threshold, double sigmaOverHorizon,
                      long horizonSeconds, long reArmAtMillis) {
    }

    /** The clamped book plus the cuts that shaped it, for the operator's target-book view. */
    public record Result(List<FusionPlanner.Target> targets, List<Cut> cuts, int trackedNames,
                         int stoppedNames) {
    }

    private static final class State {
        private int side;                 // sign of the position the peak belongs to; 0 = flat
        private BigDecimal peak;          // best mark seen since that position was opened
        private long reArmAtMillis;       // stopped until this instant; 0 = not stopped
        private Cut lastCut;
    }

    private final Params params;
    private final Map<String, State> states = new HashMap<>();

    public TrailingRiskCut(Params params) {
        this.params = params == null ? new Params(3.0) : params;
    }

    /**
     * The target book with every stopped name forced flat, and the stop state advanced by this cycle's
     * marks.
     *
     * @param targets        the planned book, already clamped by the edge gate — this runs LAST, so a
     *                       cut is the desk's final word on a name
     * @param nowMillis      wall clock for the cooldown (a duration, never a market timestamp)
     * @param horizonSeconds the desk's own holding horizon — the period the position was opened to earn
     *                       (ADR-0080 at the ADR-0082-selected rung)
     * @param intervalSeconds the cadence σ was sampled at, so it can be scaled to the horizon
     * @param volatility     the measured per-name σ source; a name it does not cover is left untouched
     * @param planParams     the planning dials the recomputed exit delta must respect
     */
    public Result apply(List<FusionPlanner.Target> targets, long nowMillis, long horizonSeconds,
                        long intervalSeconds, StreamVolatility volatility,
                        FusionPlanner.Params planParams) {
        if (targets == null || targets.isEmpty()) {
            return new Result(List.of(), List.of(), states.size(), 0);
        }
        long cooldownMillis = Math.max(1L, horizonSeconds) * 1_000L;
        List<FusionPlanner.Target> out = new ArrayList<>(targets.size());
        List<Cut> cuts = new ArrayList<>();
        int stopped = 0;
        for (FusionPlanner.Target t : targets) {
            State s = states.computeIfAbsent(t.instrument(), k -> new State());
            int side = t.currentQty() == null ? 0 : t.currentQty().signum();
            BigDecimal mark = t.price();
            trackPeak(s, side, mark);
            Cut fired = side != 0 && mark != null && !isStopped(s, nowMillis)
                    ? test(t, s, side, mark, nowMillis, cooldownMillis, horizonSeconds, intervalSeconds,
                            volatility)
                    : null;
            if (fired != null) {
                cuts.add(fired);
            }
            if (isStopped(s, nowMillis)) {
                stopped++;
                out.add(flatten(t, planParams));
            } else {
                out.add(t);
            }
        }
        prune(nowMillis);
        return new Result(out, List.copyOf(cuts), states.size(), stopped);
    }

    /** The most recent firing per instrument, newest state — disclosure for the operator. */
    public List<Cut> activeCuts(long nowMillis) {
        List<Cut> out = new ArrayList<>();
        for (State s : states.values()) {
            if (s.lastCut != null && isStopped(s, nowMillis)) {
                out.add(s.lastCut);
            }
        }
        return out;
    }

    /**
     * Advance the running best mark. The peak belongs to ONE position: a flip or a close resets it,
     * because the excursion of a position the desk no longer holds says nothing about the one it does.
     */
    private static void trackPeak(State s, int side, BigDecimal mark) {
        if (side == 0) {
            s.side = 0;
            s.peak = null;
            return;
        }
        if (mark == null || mark.signum() <= 0) {
            return; // no mark this cycle — hold the peak we have rather than resetting it
        }
        if (s.side != side || s.peak == null) {
            s.side = side;
            s.peak = mark; // a new position starts its excursion at the price it is seen at
            return;
        }
        s.peak = side > 0 ? s.peak.max(mark) : s.peak.min(mark);
    }

    /** Fire the rule if this name's adverse excursion has cleared {@code k · σ_h}; else null. */
    private Cut test(FusionPlanner.Target t, State s, int side, BigDecimal mark, long nowMillis,
                     long cooldownMillis, long horizonSeconds, long intervalSeconds,
                     StreamVolatility volatility) {
        if (s.peak == null || s.peak.signum() <= 0 || volatility == null) {
            return null;
        }
        OptionalDouble sigma = volatility.sigmaOver(t.instrument(), horizonSeconds, intervalSeconds);
        if (sigma.isEmpty()) {
            return null; // not measured ⇒ no claim (ADR-0016 / invariant 7)
        }
        // Adverse excursion from the peak, as a fraction of the peak price. Exact decimal in, explicit
        // scale and rounding on the one division, dimensionless out.
        BigDecimal adverse = side > 0 ? s.peak.subtract(mark) : mark.subtract(s.peak);
        if (adverse.signum() <= 0) {
            return null; // at the peak — nothing given back
        }
        double excursion = adverse.divide(s.peak.abs(), EXCURSION_SCALE, RoundingMode.HALF_EVEN)
                .doubleValue();
        double threshold = params.sigmaMultiple() * sigma.getAsDouble();
        if (!(excursion > threshold)) {
            return null;
        }
        s.reArmAtMillis = nowMillis + cooldownMillis;
        s.lastCut = new Cut(t.instrument(), side, s.peak, mark, excursion, threshold,
                sigma.getAsDouble(), horizonSeconds, s.reArmAtMillis);
        return s.lastCut;
    }

    private static boolean isStopped(State s, long nowMillis) {
        return s.reArmAtMillis > 0 && nowMillis < s.reArmAtMillis;
    }

    /**
     * The same target with a FLAT target quantity and the exit delta that implies. The delta comes from
     * the ordinary {@link TargetPlanner#orderDelta} against a zero target, so a cut is worked exactly
     * like any other exit — the no-trade band collapses to zero and the whole gap is a reduction, which
     * ADR-0080 trades in full this cycle.
     */
    private static FusionPlanner.Target flatten(FusionPlanner.Target t, FusionPlanner.Params planParams) {
        BigDecimal delta = TargetPlanner.orderDelta(BigDecimal.ZERO, t.currentQty(),
                planParams.bufferFraction(), planParams.adjustmentRate());
        // Belt and braces: a cut may only ever take risk off, whatever the arithmetic above produced.
        BigDecimal reducing = TargetPlanner.reduceOnly(delta, t.currentQty());
        return new FusionPlanner.Target(t.instrument(), t.combinedForecast(), t.sources(),
                t.diversificationMultiplier(), t.agreement(), t.estimable(), t.price(), BigDecimal.ZERO, t.currentQty(), reducing,
                t.contributions());
    }

    /** Forget names that are flat and out of cooldown, so the state map tracks the book, not history. */
    private void prune(long nowMillis) {
        for (Iterator<Map.Entry<String, State>> it = states.entrySet().iterator(); it.hasNext(); ) {
            State s = it.next().getValue();
            if (s.side == 0 && !isStopped(s, nowMillis)) {
                it.remove();
            }
        }
    }
}
