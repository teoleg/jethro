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
 * "average position", read off this cycle's own arithmetic with no estimator and no warm-up. Only the
 * buffer WIDTH is a dial, and it is a dimensionless fraction, not a money number.
 *
 * <p><b>What it can never do.</b> It never widens a trade the desk was not already going to make in the
 * same direction on the same aim path, it never moves the aim past the target, and it never buffers an
 * exit: a flat target (the ADR-0086 chandelier cut, the ADR-0065 orphan unwind, the ADR-0027 breaker
 * above it) snaps the aim to zero and trades the whole position, exactly as before. Where the ADR-0064
 * edge gate says a name may not increase, the order is clamped reduce-only and the aim is re-seeded to
 * where the desk will actually be, so intent cannot run away from a book that is not allowed to follow it.
 *
 * <p>Exact decimal throughout (invariant 1); the only doubles are the dimensionless rate and fraction.
 * It prices nothing and asserts no money number (invariant 7 / ADR-0016).
 */
public final class PositionBuffer {

    /** The scale every quantity here is stated at — the scale positions, orders and fills already use. */
    private static final int QTY_SCALE = 6;

    /**
     * Buffer width as a fraction of the name's average position. {@code 0.10} is Carver's published
     * buffering convention (<i>Systematic Trading</i> 2015; <i>Advanced Futures Trading Strategies</i>
     * 2023) — a cited market convention, not a self-chosen default. Dimensionless: it sizes nothing,
     * it only decides when a difference is worth paying spread for.
     */
    private final double bufferFraction;

    /**
     * instrument → intended position (the ADR-0080 aim). Derived state, seeded from the held position on
     * first sight and re-seeded whenever the desk is not permitted to act on it, so a cold start is
     * conservative rather than a jump. Touched only from the fusion tick thread.
     */
    private final Map<String, BigDecimal> aims = new HashMap<>();

    public PositionBuffer(double bufferFraction) {
        this.bufferFraction = Math.max(0.0, bufferFraction);
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
        if (targets == null || targets.isEmpty()) {
            aims.clear();
            return new Result(List.of(), Map.of(), 0, 0);
        }
        double rate = Math.max(0.0, Math.min(1.0, adjustmentRate));
        List<FusionPlanner.Target> out = new ArrayList<>(targets.size());
        Map<String, BigDecimal> snapshot = new HashMap<>(targets.size());
        int inside = 0;
        int traded = 0;
        for (FusionPlanner.Target t : targets) {
            BigDecimal held = t.currentQty() == null ? BigDecimal.ZERO : t.currentQty();
            BigDecimal target = t.targetQty() == null ? BigDecimal.ZERO : t.targetQty();
            BigDecimal aim = nextAim(t.instrument(), target, held, rate);
            BigDecimal delta = bufferedDelta(aim, held, band(target, t.combinedForecast(), held));
            if (gate != null && !gate.mayIncrease(t.instrument())) {
                // ADR-0064/0075: this name may only have risk taken OFF. Clamp, then re-seed the aim to
                // where the desk will actually be — an intent it is forbidden to act on must not
                // accumulate into one large order the moment the gate reopens.
                delta = TargetPlanner.reduceOnly(delta, held);
                aim = held.add(delta).setScale(QTY_SCALE, RoundingMode.HALF_EVEN);
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
                            t.diversificationMultiplier(), t.price(), t.targetQty(), t.currentQty(),
                            delta, t.contributions()));
        }
        aims.keySet().retainAll(snapshot.keySet()); // a name that left the book leaves no intent behind
        return new Result(out, Collections.unmodifiableMap(snapshot), inside, traded);
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
        return from.add(target.subtract(from).multiply(BigDecimal.valueOf(rate)))
                .setScale(QTY_SCALE, RoundingMode.HALF_EVEN);
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
        double f = Math.abs(forecast);
        BigDecimal scale = target.signum() != 0 && f > 0.0
                ? target.abs().multiply(BigDecimal.valueOf(Forecast.TARGET_ABS))
                        .divide(BigDecimal.valueOf(f), QTY_SCALE, RoundingMode.HALF_EVEN)
                : held.abs();
        return scale.multiply(BigDecimal.valueOf(bufferFraction))
                .setScale(QTY_SCALE, RoundingMode.HALF_EVEN);
    }

    /**
     * The order to submit this cycle: nothing inside the buffer, otherwise the gap to the aim less the
     * buffer — i.e. trade to the NEAR EDGE of the no-trade region, never all the way to the aim.
     *
     * <p>A zero aim is an exit and is never buffered: the whole position is traded, this cycle.
     */
    static BigDecimal bufferedDelta(BigDecimal aim, BigDecimal held, BigDecimal band) {
        BigDecimal gap = aim.subtract(held).setScale(QTY_SCALE, RoundingMode.HALF_EVEN);
        if (aim.signum() == 0) {
            return gap; // an exit is worked in full (ADR-0090)
        }
        if (gap.abs().compareTo(band) <= 0) {
            return BigDecimal.ZERO.setScale(QTY_SCALE);
        }
        BigDecimal edge = gap.abs().subtract(band);
        return edge.multiply(BigDecimal.valueOf(gap.signum())).setScale(QTY_SCALE, RoundingMode.HALF_EVEN);
    }
}
