package io.jethro.app.fusion;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The desk's no-trade region, applied where it can actually bind (ADR-0094): the book is traded toward
 * an <b>aim</b> — the partially-adjusted intended position — and only when the held position has drifted
 * more than a buffer away from it, and then only back to the buffer's near edge.
 *
 * <p><b>Why the ADR-0055 band cannot bind.</b> That band is {@code |target| × bufferFraction} measured
 * against {@code |target − held|}. Under ADR-0080 partial adjustment the desk deliberately does NOT go
 * to the target — it closes a fraction {@code a} of the gap per cycle, so the held position tracks an
 * exponential average of the target and sits structurally far below it whenever the target moves on a
 * timescale shorter than {@code 1/a}. The gap is then a LARGE fraction of the target every cycle, the
 * band is a small one, and the comparison has only one answer. Measured on the live target book, every
 * planned name with a non-zero gap traded at exactly the full rated fraction — the band did not suppress
 * a single order — while the desk held between 5% and 30% of its own target. The desk was paying
 * turnover on every cycle to hold a small, lagging fraction of the risk it had decided to take.
 *
 * <p><b>What replaces it.</b> Separate the two things the old band conflated — where the desk INTENDS to
 * be, and when it is worth paying spread to get there:
 * <pre>
 *   aim   ← aim + a·(target − aim)      the ADR-0080 exponential path, kept exactly
 *   aim   ← 0                           when the target is FLAT: an exit is not buffered (ADR-0090)
 *   scale = |target| · TARGET_ABS / |forecast|     the name's position at a typical-strength forecast
 *   band  = scale · bufferFraction
 *   gap   = aim − held
 *   |gap| ≤ band → 0                     inside the buffer: the desk is where it means to be
 *   otherwise    → gap − band·sgn(gap)   trade to the NEAR EDGE of the buffer, not to the aim
 * </pre>
 * The aim path is byte-identical to the position the old policy converged to, so the desk's intended
 * risk — and therefore its exposure — is unchanged by construction. What changes is that the last
 * fraction of the way is no longer bought and sold every thirty seconds: a target that oscillates
 * inside the buffer is not traded at all, and only its net drift is. That is the standard buffering
 * rule (Carver, <i>Systematic Trading</i>, Harriman House 2015, and <i>Advanced Futures Trading
 * Strategies</i>, 2023: buffer the position at a fraction of the average position and trade to the
 * buffer edge), which is also the form the optimal policy takes under PROPORTIONAL transaction costs —
 * a no-trade region with trading at its boundary (Constantinides, <i>JPE</i> 1986; Davis &amp; Norman,
 * <i>Math. of OR</i> 1990). This desk's cost is proportional: the fee is a fixed fraction of notional
 * and measured slippage is quoted in bps, so total cost is a function of QUANTITY traded, not of the
 * number of orders — which is why suppressing the oscillation, not the order count, is the lever.
 *
 * <p><b>The position scale is derived, not dialled.</b> The target is linear in the combined forecast
 * (every step between them — the ADR-0083 volatility budget, the ADR-0079 portfolio normaliser — is a
 * per-name or book-wide scalar that does not depend on forecast STRENGTH), so
 * {@code |target| · TARGET_ABS / |forecast|} is that name's position at a typical forecast: Carver's
 * "average position", read off this cycle's own arithmetic with no estimator and no warm-up.
 *
 * <p><b>And the forecast that scale is priced at is MEASURED, not nominal (ADR-0141).</b> {@code
 * TARGET_ABS} is what each SOURCE is normalised to; the band is applied to the COMBINED forecast, which
 * the ADR-0076 DM and the ADR-0124 agreement scalar have already attenuated. Pricing the average position
 * at the unattenuated constant made the release condition {@code |aim|/|target| > width × TARGET_ABS/|f|}
 * unsatisfiable for every name with {@code |f| < width × TARGET_ABS}, since ADR-0102 caps that ratio at
 * one — a permanent dead zone in which attenuations meant to reduce SIZE removed the position entirely.
 * See {@link #typicalForecastAbs}.
 *
 * <p><b>And so is the buffer WIDTH (ADR-0101).</b> Carver's 0.10 is a published convention for a desk
 * whose cost and edge he does not know; this desk measures both, per name and per source, and the width
 * that follows from them is not 0.10. See {@link #widthFor} for the derivation and the one-way
 * guarantee: a measured width is used only where it is WIDER than the convention, so it can only ever
 * remove turnover, never add it.
 *
 * <p><b>And the intent is bounded by the current target (ADR-0102).</b> The aim path is an EWMA of the
 * target sequence, so it is a convex combination of targets the desk held in the PAST — which lets it
 * outgrow, or end up on the opposite side of, the target it holds NOW. See {@link #withinTarget}: the
 * aim is clamped into the closed interval between flat and this cycle's target, which can only ever
 * shrink intent or put it back on the side the forecast is on.
 *
 * <p><b>And so is the position it STOPS AT (ADR-0132).</b> Buffering the aim bounds where the desk means
 * to be; it does not bound where the desk actually stops, which is a whole band below that. Because the
 * band is scaled by the average position at the TARGET, it routinely exceeds the aim early on the aim's
 * slow path, so the no-trade region straddles flat and reaches onto the side the forecast opposes — and a
 * wrong-side holding inside it is frozen at exactly zero delta indefinitely. See {@link #onTargetSide}:
 * the destination is held to the side of flat the target is on, which is the same bound ADR-0102 puts on
 * the intent.
 *
 * <p><b>What it can never do.</b> It never widens a trade the desk was not already going to make in the
 * same direction on the same aim path, it never moves the aim past the target, and it never buffers an
 * exit: a flat target (the ADR-0086 chandelier cut, the ADR-0065 orphan unwind, the ADR-0027 breaker
 * above it) snaps the aim to zero and trades the whole position, exactly as before. Where the desk may
 * not increase a name — the ADR-0064 edge gate, or ADR-0126's unarmed trailing stop — the order is
 * clamped reduce-only and the aim is re-seeded to where the desk will actually be, so intent cannot run
 * away from a book that is not allowed to follow it. See {@link #mayIncrease}.
 *
 * <p>Exact decimal throughout (invariant 1); the only doubles are the dimensionless rate and fraction.
 * It prices nothing and asserts no money number (invariant 7 / ADR-0016).
 */
public final class PositionBuffer {

    /** The scale every quantity here is stated at — the scale positions, orders and fills already use. */
    private static final int QTY_SCALE = 6;

    /**
     * Buffer width as a fraction of the name's average position, and the FLOOR under the measured width
     * of ADR-0101. {@code 0.10} is Carver's published buffering convention (<i>Systematic Trading</i>
     * 2015; <i>Advanced Futures Trading Strategies</i> 2023) — a cited market convention, not a
     * self-chosen default. Dimensionless: it sizes nothing, it only decides when a difference is worth
     * paying spread for.
     */
    private final double bufferFraction;

    /**
     * instrument → intended position (the ADR-0080 aim). Derived state, seeded from the held position on
     * first sight and re-seeded whenever the desk is not permitted to act on it, so a cold start is
     * conservative rather than a jump. Touched only from the fusion tick thread.
     */
    private final Map<String, BigDecimal> aims = new HashMap<>();

    /**
     * ADR-0140 — instrument → consecutive cycles this name has been ABSENT from the target list. A
     * name planned this cycle sits at zero; one that stops being plannable ages out after a full
     * measurement horizon (see {@link #retentionCycles}). Touched only from the fusion tick thread.
     */
    private final Map<String, Integer> absentCycles = new HashMap<>();

    /** ADR-0140 — durable home for {@link #aims}; null means in-memory only (the pre-ADR-0140 path). */
    private final AimStore aimStore;

    /** ADR-0140 — the restore is attempted once, on the first cycle, not on every one. */
    private boolean restored;

    public PositionBuffer(double bufferFraction) {
        this(bufferFraction, null);
    }

    /**
     * ADR-0140 — as above, with a durable home for the aim. {@code aimStore} null leaves every path
     * byte-identical to the in-memory buffer.
     */
    public PositionBuffer(double bufferFraction, AimStore aimStore) {
        this.bufferFraction = Math.max(0.0, bufferFraction);
        this.aimStore = aimStore;
    }

    /** The buffered book plus the aims it was traded against — the aims are operator-visible telemetry. */
    public record Result(List<FusionPlanner.Target> targets, Map<String, BigDecimal> aims,
                         int insideBuffer, int traded) {
    }

    /**
     * Re-derives each name's order delta from its aim and buffer. {@code gate} may be null (no edge
     * measurement wired), in which case no reduce-only clamp applies — exactly as elsewhere.
     *
     * @param adjustmentRate the ADR-0080 derived partial-adjustment fraction for this cycle
     */
    public Result apply(List<FusionPlanner.Target> targets, EdgeGate.Decision gate, double adjustmentRate) {
        return apply(targets, gate, adjustmentRate, null);
    }

    /**
     * ADR-0126 — as above, plus the second reason a name may not have risk ADDED to it: its ADR-0086
     * trailing-stop σ sensor has not warmed, so the desk has no measured distance at which it would cut
     * the position and the risk cut cannot protect it. {@code stopArmed} answers "can this name be
     * stopped out?"; null means unwired, which leaves every path byte-identical.
     *
     * @param adjustmentRate the ADR-0080 derived partial-adjustment fraction for this cycle
     * @param stopArmed      per-name predicate: true when the risk-cut sensor can price this name's stop
     */
    public Result apply(List<FusionPlanner.Target> targets, EdgeGate.Decision gate, double adjustmentRate,
                        java.util.function.Predicate<String> stopArmed) {
        return apply(targets, gate, adjustmentRate, stopArmed, null, 0.0);
    }

    /**
     * ADR-0145 — as above, plus the mirror of the ADR-0059 conviction floor. A reduction the PLANNER
     * authored — the forecast-implied target has decayed below the holding — routes only when that
     * forecast is strong enough that it would have been allowed to OPEN the position; a reduction a risk
     * control authored is never held. {@code plannedTargets} maps a name to the target the planner
     * produced BEFORE any control ran; null means unwired, which leaves every path byte-identical.
     *
     * @param adjustmentRate     the ADR-0080 derived partial-adjustment fraction for this cycle
     * @param stopArmed          per-name predicate: true when the risk-cut sensor can price this name's stop
     * @param plannedTargets     name → the planner's own target, before the risk controls; null = unwired
     * @param minForecastToRoute the ADR-0059 conviction floor; at or below zero the floor is off
     */
    public Result apply(List<FusionPlanner.Target> targets, EdgeGate.Decision gate, double adjustmentRate,
                        java.util.function.Predicate<String> stopArmed,
                        java.util.function.Function<String, BigDecimal> plannedTargets,
                        double minForecastToRoute) {
        ensureRestored();
        if (targets == null || targets.isEmpty()) {
            // ADR-0140: an empty plan is a cycle in which every name was absent, not proof that the
            // desk has abandoned its intent. Age the map on the same clock as any other absence.
            ageAndRetain(java.util.Set.of(), Math.max(0.0, Math.min(1.0, adjustmentRate)));
            persist();
            return new Result(List.of(), Map.of(), 0, 0);
        }
        double rate = Math.max(0.0, Math.min(1.0, adjustmentRate));
        double edgeBps = passingEdgeBps(gate); // ADR-0101: measured once, the same for every name
        // ADR-0141: the forecast strength the "average position" is priced at, measured on this cycle's
        // own cross-section rather than assumed at the nominal scaling constant. Measured once, the same
        // for every name — like the edge above.
        double typicalForecast = typicalForecastAbs(targets);
        List<FusionPlanner.Target> out = new ArrayList<>(targets.size());
        Map<String, BigDecimal> snapshot = new HashMap<>(targets.size());
        int inside = 0;
        int traded = 0;
        for (FusionPlanner.Target t : targets) {
            BigDecimal held = t.currentQty() == null ? BigDecimal.ZERO : t.currentQty();
            BigDecimal target = t.targetQty() == null ? BigDecimal.ZERO : t.targetQty();
            BigDecimal aim = nextAim(t.instrument(), target, held, rate);
            double width = widthFor(t.instrument(), gate, edgeBps);
            BigDecimal delta = bufferedDelta(aim, held,
                    band(target, t.combinedForecast(), held, width, typicalForecast), target, rate);
            if (!mayIncrease(gate, stopArmed, t.instrument())) {
                // ADR-0064/0075: this name may only have risk taken OFF. Clamp, then re-seed the aim to
                // where the desk will actually be — an intent it is forbidden to act on must not
                // accumulate into one large order the moment the gate reopens.
                // ADR-0118: an intent of FLAT in a name the gate forbids rebuilding is an EXIT, and an
                // exit is not buffered (ADR-0090). Otherwise the desk holds a position its own forecast
                // is on the other side of, at a band scaled by a target it may never reach.
                delta = isTrappedExit(aim, held)
                        ? held.negate().setScale(QTY_SCALE, RoundingMode.HALF_EVEN)
                        : TargetPlanner.reduceOnly(delta, held);
                aim = held.add(delta).setScale(QTY_SCALE, RoundingMode.HALF_EVEN);
            } else if (plannedTargets != null) {
                // ADR-0145: the conviction floor, applied to the exit as well as the entry. A reduction
                // the forecast alone authored — its target has decayed below the holding — is held back
                // unless that forecast would have been strong enough to open the position; whatever a
                // risk control authored still routes in full. Re-seed the aim to where the desk will
                // actually be, for the same reason the clamp above does: an intent it is not acting on
                // must not accumulate into one large unwind that fires the moment conviction returns.
                BigDecimal convicted = ConvictionHold.apply(delta, held, plannedTargets.apply(t.instrument()),
                        target, t.combinedForecast(), minForecastToRoute);
                if (convicted.compareTo(delta) != 0) {
                    delta = convicted;
                    aim = held.add(delta).setScale(QTY_SCALE, RoundingMode.HALF_EVEN);
                }
            }
            aims.put(t.instrument(), aim);
            snapshot.put(t.instrument(), aim);
            if (delta.signum() == 0) {
                inside++;
            } else {
                traded++;
            }
            out.add(delta.compareTo(t.deltaQty() == null ? BigDecimal.ZERO : t.deltaQty()) == 0 ? t
                    : new FusionPlanner.Target(t.instrument(), t.combinedForecast(), t.sources(),
                            t.diversificationMultiplier(), t.agreement(), t.price(), t.targetQty(), t.currentQty(),
                            delta, t.contributions()));
        }
        ageAndRetain(snapshot.keySet(), rate); // ADR-0140: absence ages the intent, it does not erase it
        persist();
        return new Result(out, Collections.unmodifiableMap(snapshot), inside, traded);
    }

    /**
     * ADR-0140 — restore the aim path from its durable home, once, on the first cycle of the process.
     *
     * <p>A restored aim is not acted on directly: it enters {@link #nextAim} as the previous aim, is
     * stepped toward THIS cycle's target at THIS cycle's rate, and is then clamped by ADR-0102's
     * {@link #withinTarget} into the closed interval between flat and the current target. So a restored
     * value can never exceed the current target, never oppose it, and never survive a change of view —
     * it can only spare the desk from re-paying a transient it has already served.
     */
    private void ensureRestored() {
        if (restored) {
            return;
        }
        restored = true;
        if (aimStore == null) {
            return;
        }
        Map<String, BigDecimal> stored = aimStore.load();
        if (stored != null) {
            stored.forEach((instrument, aim) -> {
                if (instrument != null && aim != null) {
                    aims.put(instrument, aim.setScale(QTY_SCALE, RoundingMode.HALF_EVEN));
                }
            });
        }
    }

    /** ADR-0140 — write the aim path through to its durable home. Best-effort by contract. */
    private void persist() {
        if (aimStore != null) {
            aimStore.save(Map.copyOf(aims));
        }
    }

    /**
     * ADR-0140 — the aim of a name absent from this cycle's plan is AGED, not deleted.
     *
     * <h3>The defect this replaces</h3>
     * The map was pruned with {@code retainAll(planned)}, so a name that fell out of the target list
     * for a single cycle — because a sensor went quiet, a print went stale, or the selector rotated —
     * lost its whole intent and restarted from the held quantity. Combined with the same reset at every
     * process start (the map was in-memory), the ADR-0080 path could never complete its transient: with
     * the aim rising as {@code 1 − (1−a)^n} toward the target and the ADR-0094 buffer only releasing an
     * order once {@code |aim|} exceeds a band scaled by the name's average position, a repeatedly-reset
     * aim never reaches the release fraction and the delta reads exactly zero, cycle after cycle.
     *
     * <h3>The retention window is derived, not dialled</h3>
     * The ADR-0080 identity is {@code a = 1 − e^(−c/h)} for cycle length {@code c} and evidence horizon
     * {@code h}, so {@code −1 / ln(1−a) = h/c} — the number of cycles in exactly one measurement
     * horizon, read straight off the rate the caller already passed in. That is the natural life of an
     * intent: an aim whose name has been unplannable for a full horizon describes a view the desk no
     * longer has evidence for, and is dropped. No number is introduced (invariant 7 / ADR-0016).
     *
     * <p>A degenerate rate keeps the old semantics: {@code rate ≤ 0} means the path never moves, so
     * there is no transient to protect and an absent name is dropped at once.
     */
    private void ageAndRetain(java.util.Set<String> planned, double rate) {
        int window = retentionCycles(rate);
        aims.keySet().removeIf(instrument -> {
            if (planned.contains(instrument)) {
                absentCycles.remove(instrument);
                return false;
            }
            int absent = absentCycles.merge(instrument, 1, Integer::sum);
            if (absent > window) {
                absentCycles.remove(instrument);
                return true;
            }
            return false;
        });
        absentCycles.keySet().retainAll(aims.keySet());
    }

    /**
     * Cycles of absence an intent survives: {@code −1 / ln(1 − rate)}, which is the ADR-0080 identity's
     * {@code h/c} — one full evidence horizon expressed in planner cycles. Zero for a rate that cannot
     * move the path at all, and at least one wherever the horizon is a single cycle.
     */
    static int retentionCycles(double rate) {
        if (!(rate > 0.0)) {
            return 0; // a path that never advances has no transient worth protecting
        }
        if (rate >= 1.0) {
            return 1; // horizon == cycle: the aim reaches target in one step
        }
        double cycles = -1.0 / Math.log1p(-rate);
        if (!Double.isFinite(cycles) || cycles < 1.0) {
            return 1;
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.round(cycles));
    }

    /**
     * May this name have risk ADDED to it this cycle? Two independent reasons say no, and either alone
     * is sufficient:
     * <ul>
     *   <li><b>ADR-0064/0072</b> — the edge gate has no measured edge that beats this name's measured
     *       round trip. {@code gate} null means the measurement is not wired, which is silence, not a
     *       veto.</li>
     *   <li><b>ADR-0126</b> — the name's ADR-0086 trailing-stop σ sensor has not warmed, so there is no
     *       measured distance at which the desk would cut it. A position that cannot be stopped out is
     *       one the desk's own risk control cannot protect, and opening it is taking risk it has no
     *       exit for. {@code stopArmed} null means the sensor is not wired, which is again silence.</li>
     * </ul>
     *
     * <p><b>Why this is a conjunction and not a branch inside the gate's.</b> The σ-cold veto is
     * evaluated whether or not the edge gate is wired or enabled. A rule whose body only runs inside
     * {@code if (gate != null && !gate.mayIncrease(...))} is dead the moment the gate is switched off —
     * which is the desk's current configuration under ADR-0122 — and a risk control that disappears with
     * an unrelated dial is not a control. The two reasons are therefore combined here, at the one place
     * the delta is finally decided.
     */
    private static boolean mayIncrease(EdgeGate.Decision gate,
                                       java.util.function.Predicate<String> stopArmed, String instrument) {
        if (gate != null && !gate.mayIncrease(instrument)) {
            return false;
        }
        return stopArmed == null || stopArmed.test(instrument);
    }

    /**
     * ADR-0118 — is this name's position an exit the buffer would otherwise trap? True when the desk's
     * settled intent is FLAT while it still holds something, <em>in a name the edge gate has put
     * reduce-only</em> (the caller's branch).
     *
     * <h3>How the trap forms</h3>
     * A flat aim arises two ways. Either a control planned the name flat — {@link #nextAim} snaps the aim
     * to zero and {@link #bufferedDelta} already works that in full — or ADR-0102's {@code withinTarget}
     * clamped it, which happens for exactly one reason: <b>the held position is on the side the current
     * forecast opposes</b>, so no position between flat and the target contains it and the intent is held
     * at flat. The second case reaches the buffer as an ordinary rebalance and is measured against a band
     * scaled by {@code |target| × TARGET_ABS / |forecast|} — the average position at the TARGET. Under a
     * shut gate the desk may never take that target, so the band is a no-trade region sized by a position
     * it is forbidden to hold, and any wrong-side holding smaller than it is frozen: the delta is exactly
     * zero every cycle, indefinitely, and the position is not being wound down — it is stuck.
     *
     * <p>Diagnosed on the live book: short 1 AAPL against a target of +6.031064 at forecast +0.436599,
     * giving an average position of 138.137520 and a band of 13.813752 against a gap of 1.000000. The
     * desk carried a short its own model wanted long, hedged it with ES — so it paid gross exposure on
     * BOTH legs — and had no path to closing either.
     *
     * <h3>Why this is not ADR-0080's mistake again</h3>
     * ADR-0090 narrowed "work every reduction in full" because a mean-reverting forecast crosses the held
     * position many times inside one horizon, and liquidating on each crossing paid a full round trip per
     * wobble while never reaching size. That failure needs the desk to be able to REBUILD. This branch
     * fires only where the gate has taken rebuilding away: the sole trade available in the name is a cut,
     * so there is no round trip to churn — the position can be closed once and not reopened until measured
     * evidence reopens the gate, which moves on the gate's timescale (hours of accumulated cohorts), not
     * the forecast's. A gate that is OPEN leaves every path here byte-identical.
     *
     * <h3>What it can never do</h3>
     * It resolves to flat and nothing else, so {@code |held + delta| = 0 < |held|}: strictly
     * risk-reducing, never opening, never enlarging, never flipping a position onto a new side. Exact
     * decimal, no number introduced (invariant 1, invariant 7 / ADR-0016), and it sits above the
     * deterministic floor — the pre-trade guardrail and the firm breaker still have the last word.
     */
    private static boolean isTrappedExit(BigDecimal aim, BigDecimal held) {
        return aim.signum() == 0 && held.signum() != 0;
    }

    /**
     * The intended position after this cycle: the ADR-0080 exponential path {@code aim + a·(target −
     * aim)}, seeded at the held quantity on first sight of a name.
     *
     * <p>A FLAT target snaps the aim to zero rather than decaying toward it. Every control that means
     * "get out" says so by planning the name flat, and ADR-0090 already works such a target in full;
     * letting the aim e-fold toward zero instead would turn a cut into a slow bleed and — worse — leave
     * the buffer holding a stopped-out position for cycles after the stop fired.
     */
    private BigDecimal nextAim(String instrument, BigDecimal target, BigDecimal held, double rate) {
        if (target.signum() == 0) {
            return BigDecimal.ZERO.setScale(QTY_SCALE);
        }
        BigDecimal previous = aims.get(instrument);
        BigDecimal from = previous == null ? held : previous;
        BigDecimal stepped = from.add(target.subtract(from).multiply(BigDecimal.valueOf(rate)))
                .setScale(QTY_SCALE, RoundingMode.HALF_EVEN);
        return withinTarget(stepped, target);
    }

    /**
     * ADR-0102 — the intent, held to the positions this cycle's own target can justify: the closed
     * interval between FLAT and the target.
     *
     * <pre>
     *   aim' = min(max(aim, min(0, target)), max(0, target))
     * </pre>
     *
     * <h3>Why the path needs it</h3>
     * {@link #nextAim} is an exponential moving average of the target sequence: unrolling it gives
     * {@code aimₜ = a·Σₖ (1−a)ᵏ·Tₜ₋ₖ} (plus the decaying seed), whose weights are non-negative and sum
     * to one. So the aim is a convex combination of PAST targets — it lies in the hull of the targets
     * the desk has HELD, which is not the hull of the target it holds NOW. Two things follow whenever
     * the target moves faster than {@code 1/a}, and this desk's only measured source is a
     * mean-reverting one that does exactly that:
     * <ul>
     *   <li><b>Overshoot.</b> When the target shrinks, the aim is left larger than it — the desk
     *       intends, and trades toward, more risk in the name than its current evidence asks for.</li>
     *   <li><b>Inversion.</b> When the target changes SIDE, the aim spends the whole decay on the old
     *       side — the desk intends, and trades toward, a position its current evidence says is the
     *       wrong way round, and pays spread to get there.</li>
     * </ul>
     * Neither is what partial adjustment means. Gârleanu &amp; Pedersen's optimal policy trades a
     * fraction of the way to an aim that is itself a weighted average of the CURRENT and EXPECTED
     * FUTURE targets (<i>JF</i> 68(6), 2013, §III) — every element of which is a position the model
     * wants now or expects to want. Averaging over a realised past instead admits neither. The
     * clamp restores the property the policy assumes without touching the rate: approach the target
     * slowly, but never intend past it and never intend against it.
     *
     * <h3>What it can never do</h3>
     * {@code |aim'| ≤ |aim|} and {@code sgn(aim') ∈ {0, sgn(target)}}, both by construction — so this
     * can only ever shrink the desk's intent or move it onto the side its own forecast is on. It is
     * strictly one-way: it never opens a position, never enlarges one, never flips one onto a side the
     * target does not name, and never widens or narrows a band. A flat target is untouched (that branch
     * returns before this one), so the ADR-0086 cut, the ADR-0065 unwind and the deterministic floor
     * above them keep their exact semantics. And when the aim already lies between flat and the target
     * — the ordinary case, every cycle the target is stable — the path is byte-identical to ADR-0094's.
     *
     * <p>No number is introduced: the bound is the target the planner already computed. Exact decimal
     * throughout (invariant 1); nothing here prices or sizes anything (invariant 7 / ADR-0016).
     */
    static BigDecimal withinTarget(BigDecimal aim, BigDecimal target) {
        if (aim.signum() == 0 || target.signum() == 0) {
            return aim;
        }
        if (aim.signum() != target.signum()) {
            return BigDecimal.ZERO.setScale(QTY_SCALE); // intent never opposes the current view
        }
        return aim.abs().compareTo(target.abs()) > 0
                ? target.setScale(QTY_SCALE, RoundingMode.HALF_EVEN) // never intend past the target
                : aim;
    }

    /**
     * Half-width of the no-trade region for one name: {@code bufferFraction × averagePosition}, where the
     * average position is what this name carries at a typical-strength forecast,
     * {@code |target| × TARGET_ABS / |forecast|}.
     *
     * <p>When the forecast or the target is degenerate — no view, or a name planned flat — that ratio
     * says nothing, and the only position at stake is the one already held, so the buffer is taken on
     * {@code |held|}. That keeps an unwind proportionate instead of either freezing it (a zero band
     * cannot happen with a non-zero holding) or inventing a scale for it.
     */
    BigDecimal band(BigDecimal target, double forecast, BigDecimal held) {
        return band(target, forecast, held, bufferFraction);
    }

    /** As above at an explicit width — the ADR-0101 measured one, or the convention when unmeasured. */
    BigDecimal band(BigDecimal target, double forecast, BigDecimal held, double width) {
        return band(target, forecast, held, width, Forecast.TARGET_ABS);
    }

    /**
     * As above with the forecast strength the average position is priced at stated explicitly — the
     * ADR-0141 measured cross-section, or {@link Forecast#TARGET_ABS} where there is nothing to measure.
     *
     * <p>{@code typicalForecastAbs} enters exactly where {@code TARGET_ABS} did, so the band is still
     * {@code width × |target| × E|f| / |forecast|} — Carver's "a fraction of the average position" — with
     * {@code E|f|} read off the desk's own arithmetic instead of assumed.
     */
    BigDecimal band(BigDecimal target, double forecast, BigDecimal held, double width,
                    double typicalForecastAbs) {
        double f = Math.abs(forecast);
        double typical = typicalForecastAbs > 0.0
                ? Math.min(Forecast.TARGET_ABS, typicalForecastAbs)
                : Forecast.TARGET_ABS;
        BigDecimal scale = target.signum() != 0 && f > 0.0
                ? target.abs().multiply(BigDecimal.valueOf(typical))
                        .divide(BigDecimal.valueOf(f), QTY_SCALE, RoundingMode.HALF_EVEN)
                : held.abs();
        return scale.multiply(BigDecimal.valueOf(width))
                .setScale(QTY_SCALE, RoundingMode.HALF_EVEN);
    }

    /**
     * ADR-0141 — the forecast strength this desk's combiner actually produces: the mean {@code |f|} over
     * the names it planned a view on THIS cycle. Zero when it planned fewer than two, which restores
     * {@link Forecast#TARGET_ABS} at the call site.
     *
     * <h3>The dead zone this removes</h3>
     * The band is {@code width × |target| × S / |f|} for a scaling constant {@code S}, and ADR-0102 bounds
     * the aim into the interval between flat and the target, so a name opening from flat routes its first
     * order only when
     * <pre>
     *   |aim| &gt; band   ⟺   |aim|/|target| &gt; width × S / |f|      with   |aim|/|target| ≤ 1
     * </pre>
     * which is <b>unsatisfiable for every name with {@code |f| &lt; width × S}</b>. At {@code S = TARGET_ABS}
     * that threshold is a fixed forecast magnitude, and the desk's combined forecasts sit below it as a
     * matter of course: the ADR-0076 DM and the ADR-0124 agreement scalar are both attenuations, and both
     * were specified as size reductions ("can only ever SHRINK the combined value") — but the band was
     * still priced at the UNATTENUATED constant, so what they actually delivered was a position of exactly
     * zero, permanently, rather than a smaller one. An 80% haircut to conviction became a 100% haircut to
     * the position. Read off the live plan the two names carrying a corroborated view needed
     * {@code |aim|/|target|} of 0.8239 and 3.1807 respectively — the second is greater than one, so no aim
     * path of any length could ever have opened it, and {@code insideBuffer} read every planned name.
     *
     * <h3>Why the cross-section, and why it needs no estimator</h3>
     * {@code S} is Carver's expected absolute forecast, the constant that turns a forecast into "how big is
     * a typical position" (<i>Systematic Trading</i>, Harriman House 2015). {@code TARGET_ABS} is what each
     * SOURCE is scaled to before combination — {@code forecastScalars} normalises every source to it — but
     * the band is applied to the COMBINED forecast, which is the sources' weighted average times two
     * scalars in {@code (0, 1]} and one in {@code [1, 2.5]}. Its expected absolute value is therefore a
     * different, smaller number, and it is one the desk computes for itself every cycle. Taking the mean
     * over the current plan keeps the property the existing scale already has and the class doc already
     * claims — "read off this cycle's own arithmetic with no estimator and no warm-up" — so it survives a
     * restart intact, needs no persistence, and self-calibrates to any feed's forecast distribution
     * (never a hardcoded level). Names planned with no view are excluded: a name the desk has no opinion on
     * is not one of its typical positions.
     *
     * <h3>What it can never do</h3>
     * Capped at {@code TARGET_ABS} at the call site, so the band is never WIDER than the pre-ADR-0141 one:
     * this can only ever release a trade the desk's own arithmetic already wanted, never freeze one it was
     * making. It touches neither the aim path nor the target, so the desk's intended risk is unchanged
     * quantity for quantity — only the threshold at which intent becomes an order moves. Every control
     * below it is untouched: the ADR-0064 edge gate, the ADR-0126 σ-cold veto, the ADR-0083 vol budget, the
     * ADR-0137 gross cap, the pre-trade guardrail and the firm drawdown breaker all still have the last
     * word, so a released order is still only filled if every deterministic floor permits it.
     *
     * <p>Dimensionless — a forecast magnitude, never a size or a price (invariant 7 / ADR-0016), and it
     * introduces no number at all: the value is the mean of forecasts the planner already computed.
     */
    static double typicalForecastAbs(List<FusionPlanner.Target> targets) {
        double sum = 0.0;
        int n = 0;
        for (FusionPlanner.Target t : targets) {
            double f = Math.abs(t.combinedForecast());
            if (f > 0.0) {
                sum += f;
                n++;
            }
        }
        // n < 2 is not a cross-section. At n = 1 the mean IS the datum, so E|f|/|f| is identically 1 and
        // the band would collapse to width x |target| for every forecast strength — the statistic
        // measuring nothing but itself, the same degeneracy ADR-0124 rejected at one effective source.
        // Below two names the desk has not measured its own forecast distribution and makes no claim.
        return n < 2 ? 0.0 : sum / n;
    }

    /**
     * ADR-0101 — the buffer width this name's OWN measured cost and the desk's OWN measured edge imply,
     * floored at Carver's convention.
     *
     * <h3>The derivation</h3>
     * Hold a position {@code n} (as a multiple of the name's average position) whose view earns
     * {@code μ} per unit over the horizon the edge was measured at, against a quadratic risk penalty —
     * the objective every step of this planner is linear in:
     * <pre>
     *   U(n) = μ·n − ½λσ²n²      maximised at the aim  a = μ/(λσ²)
     *   U(a) − U(h) = ½λσ²(a−h)²        the value of closing a gap g = a − h
     *   cost of closing it = C·|g|      C = the name's MEASURED round trip, proportional in quantity
     * </pre>
     * Rebalancing is worth its cost exactly when {@code ½λσ²g² > C|g|}, i.e. when
     * {@code |g| > 2C/(λσ²)}; substituting {@code λσ² = μ/a} gives the no-trade half-width
     * <pre>
     *   band = a · (2C/μ)                       width = 2C/μ, a pure ratio of two measured bps figures
     * </pre>
     * so the width the desk should use is its own cost-to-edge ratio and nothing else. The policy shape
     * — a no-trade region traded to its near EDGE — is unchanged and is the one proportional costs call
     * for (Constantinides, <i>JPE</i> 1986; Davis &amp; Norman, <i>Math. of OR</i> 1990); this is the
     * myopic benefit-versus-cost threshold for its width, the standard closed form (Grinold &amp; Kahn,
     * <i>Active Portfolio Management</i> 2e ch. 16), stated here as such and not as the exact dynamic
     * boundary.
     *
     * <h3>Why the convention was wrong for THIS desk</h3>
     * 0.10 is the width of a desk whose {@code 2C/μ} happens to be 0.10. Ours is not: the desk trades on
     * one measured source and charges every name a measured round trip, and the ratio of the two is
     * several times that — so the last tenth of every position was being bought and sold at a cost the
     * desk's own arithmetic says the position is not worth paying.
     *
     * <h3>Inputs, and what happens without them</h3>
     * {@code C} is this name's own measured round trip, falling back to the desk blend for a name that
     * has never filled — the identical convention {@link EdgeGate.Decision#mayIncrease(String)} already
     * applies, so cost is read one way everywhere. {@code μ} is the gross expectancy of the
     * best-evidenced source that PASSES the gate, at the ADR-0082 rung the evidence selected: the very
     * reading that admitted the desk to trade at all. Gross, not net — the cost is charged once, on the
     * {@code C} side, and netting it off {@code μ} too would charge it twice.
     *
     * <p>With no gate, no passing source, a non-positive measured edge or a non-positive measured cost
     * there is no measurement and therefore no claim to make: the convention stands exactly as today
     * (invariant 7 / ADR-0016 — a number that gates money is measured or cited, never invented).
     *
     * <h3>One-way</h3>
     * The measured width is taken only where it is WIDER than the convention and is capped at one
     * average position. So this can only ever suppress a trade the desk would have made, never add one
     * and never enlarge one; the aim path, and therefore the desk's intended exposure, is untouched
     * quantity for quantity; and an EXIT is still not buffered at all — a flat target snaps the aim to
     * zero and {@link #bufferedDelta} trades it in full, so the ADR-0086 cut, the ADR-0065 unwind and
     * the deterministic floor above them are unaffected by any width.
     */
    double widthFor(String instrument, EdgeGate.Decision gate, double edgeBps) {
        if (gate == null || !(edgeBps > 0.0)) {
            return bufferFraction;
        }
        Double own = instrument == null ? null : gate.roundTripBpsByInstrument().get(instrument);
        double costBps = own == null ? gate.roundTripCostBps() : own;
        if (!(costBps > 0.0)) {
            return bufferFraction;
        }
        double derived = 2.0 * costBps / edgeBps;
        return Math.max(bufferFraction, Math.min(1.0, derived));
    }

    /**
     * The gross expectancy the desk is trading on: the best-evidenced source that clears the gate
     * ({@code sources} is already ordered best-evidenced first). {@code 0} when nothing passes — no
     * evidence, no derived width.
     */
    static double passingEdgeBps(EdgeGate.Decision gate) {
        if (gate == null) {
            return 0.0;
        }
        for (EdgeGate.SourceEdge e : gate.sources()) {
            if (e.passes()) {
                return e.avgReturnBps();
            }
        }
        return 0.0;
    }

    /**
     * The order to submit this cycle: nothing inside the buffer, otherwise the gap to the aim less the
     * buffer — i.e. trade to the NEAR EDGE of the no-trade region, never all the way to the aim.
     *
     * <p><b>An EXIT is what a control ORDERED, not what the arithmetic happens to read (ADR-0107).</b>
     * Every control that means "get out" says so by planning the name FLAT — the ADR-0086 chandelier
     * cut, the ADR-0065 orphan unwind, the ADR-0027 breaker above them — and a flat target is worked in
     * full, unbuffered and unrated, exactly as ADR-0090 works it. That is the {@code target == 0}
     * branch and it is unchanged.
     *
     * <p>What is NOT an exit is a change of view. ADR-0102 clamps an intent that has ended up on the
     * wrong side of the current target to flat, in ONE step — so on the cycle a mean-reverting forecast
     * crosses the held position, {@code aim} becomes 0 while the target is very much alive. Keying the
     * unbuffered branch on {@code aim == 0} read that as a cut and liquidated the whole accumulated
     * position at market, which is precisely the round trip ADR-0090 removed, reintroduced for the one
     * case this desk's only measured source produces most often. Two things follow, and only in that
     * case:
     * <ul>
     *   <li>the move is <b>buffered</b> like any other — traded to the near edge of the no-trade
     *       region, not through it;</li>
     *   <li>the part of it that <b>unwinds the holding</b> is worked at the ADR-0080 derived rate,
     *       because the aim reached the other side of flat by a jump rather than by a rated step, so
     *       the rate the aim path carries everywhere else is missing from exactly this gap.</li>
     * </ul>
     * The part that would OPEN on the aim's own side is left alone: that side is on the aim path and is
     * already rated by it.
     *
     * <p>Strictly one-way. {@code |edge| ≤ |gap|} and the rate is in [0, 1], so the order returned is
     * never larger, and never of a different sign, than the one this method returned before — it can
     * only ever trade LESS. It never opens a position the desk was not already opening, and it can
     * never slow a cut a risk control ordered, because such a cut arrives with a flat target and
     * returns above.
     *
     * @param target the planner's target for this name — flat means a control ordered the exit
     * @param adjustmentRate the ADR-0080 derived partial-adjustment fraction for this cycle
     */
    static BigDecimal bufferedDelta(BigDecimal aim, BigDecimal held, BigDecimal band, BigDecimal target,
                                    double adjustmentRate) {
        BigDecimal gap = aim.subtract(held).setScale(QTY_SCALE, RoundingMode.HALF_EVEN);
        if (target.signum() == 0) {
            return gap; // a control ordered the exit — worked in full (ADR-0090/0086/0065)
        }
        BigDecimal edge = gap.abs().compareTo(band) <= 0
                ? BigDecimal.ZERO.setScale(QTY_SCALE)
                : gap.abs().subtract(band).multiply(BigDecimal.valueOf(gap.signum()))
                        .setScale(QTY_SCALE, RoundingMode.HALF_EVEN);
        edge = onTargetSide(edge, aim, held, target); // ADR-0132
        if (edge.signum() == 0) {
            return BigDecimal.ZERO.setScale(QTY_SCALE);
        }
        if (held.signum() == 0 || aim.signum() == held.signum()) {
            return edge; // the aim moved by a rated step on its own side — ADR-0094, unchanged
        }
        // The intent crossed flat in one step: rate the half of the move that unwinds the holding.
        BigDecimal unwind = TargetPlanner.reduceOnly(edge, held);
        double rate = Math.max(0.0, Math.min(1.0, adjustmentRate));
        return edge.subtract(unwind)
                .add(unwind.multiply(BigDecimal.valueOf(rate)))
                .setScale(QTY_SCALE, RoundingMode.HALF_EVEN);
    }

    /**
     * ADR-0132 — the position the buffer STOPS AT, held to the side of flat the current target is on.
     *
     * <h3>The hole this closes</h3>
     * ADR-0094 buffers around the <em>aim</em> and trades to the near edge, so the position the desk
     * settles at is {@code aim − band·sgn(gap)}. ADR-0102 confines the aim to the closed interval between
     * flat and the target, but nothing confined that destination — and the band is scaled by
     * {@code |target| × TARGET_ABS / |forecast|}, the average position at the TARGET, which is routinely
     * many times the aim early on the aim's slow ADR-0080 path. Subtracting a whole band from a small aim
     * lands past flat: the no-trade region straddles zero and extends onto the side the desk's own
     * forecast opposes. A holding on that side is then either frozen at exactly zero delta (it sits inside
     * the region) or traded toward a destination that is still on the wrong side. Either way the desk
     * intends to keep a position its current view contradicts, pays gross exposure on it, and pays again
     * on the hedge sized against it.
     *
     * <p>ADR-0118 diagnosed exactly this position — short 1 AAPL against a target of +6.031064 at forecast
     * +0.436599, band 13.813752 against a gap of 1.000000 — but scoped its remedy to
     * {@link #isTrappedExit}, which only runs where {@link #mayIncrease} is false. With the edge gate off
     * (ADR-0122, the desk's shipped configuration) and the ADR-0126 σ sensors warm, that branch never
     * runs, so the trap it describes was live on the ordinary path the whole time.
     *
     * <h3>The rule</h3>
     * A destination on the opposite side of flat from the target is replaced by flat. Nothing else moves:
     * the band, the aim path and the ADR-0107 rating are untouched, and a destination already on the
     * target's side (or at flat) is returned unchanged, so every same-side rebalance is byte-identical.
     *
     * <h3>Why it can only ever reduce risk</h3>
     * The clamp resolves the destination to flat, so {@code |held + delta'| = 0 ≤ |held|}: it never opens
     * a position, never enlarges one, and never flips one onto a new side. It also never trades past the
     * aim, i.e. {@code |delta'| ≤ |gap|}, because it fires only when {@code held} is on the side the target
     * opposes: with {@code sgn(aim) ∈ {0, sgn(target)}} the aim and the holding are then on opposite sides
     * of flat (or the aim is flat), so {@code |gap| = |aim| + |held| ≥ |held| = |delta'|}. And it fires
     * <em>only</em> there — for a holding on the target's own side with the aim in ADR-0102's interval the
     * destination is {@code held + edge ≥ held > 0} when {@code gap ≥ 0} and {@code aim + band ≥ 0} when
     * {@code gap < 0}, neither of which can cross flat.
     *
     * <p>Where ADR-0102's guarantee does not hold for the aim it is handed — an aim on neither flat nor
     * the target's side, which {@link #nextAim} cannot produce — there is no interval to hold the
     * destination to and no claim to make, so the delta is returned untouched. A flat target never reaches
     * here (that branch returns above), so the ADR-0086 cut, the ADR-0065 unwind and the deterministic
     * floor above them keep their exact semantics.
     *
     * <p>Exact decimal (invariant 1). It introduces no number at all — the bound is flat (invariant 7 /
     * ADR-0016).
     */
    static BigDecimal onTargetSide(BigDecimal delta, BigDecimal aim, BigDecimal held, BigDecimal target) {
        if (target.signum() == 0 || (aim.signum() != 0 && aim.signum() != target.signum())) {
            return delta; // no ADR-0102 interval to hold the destination to
        }
        BigDecimal dest = held.add(delta);
        if (dest.signum() == 0 || dest.signum() == target.signum()) {
            return delta; // already where the current view can justify being
        }
        return held.negate().setScale(QTY_SCALE, RoundingMode.HALF_EVEN);
    }
}
