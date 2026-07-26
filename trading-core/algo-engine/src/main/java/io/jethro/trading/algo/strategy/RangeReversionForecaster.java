package io.jethro.trading.algo.strategy;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A continuous, self-calibrating <b>mean-reversion sensor</b>: how stretched is this name against its
 * own recent range right now, and how much of that stretch is worth fading? (ADR-0070.)
 *
 * <p><b>Why the desk needs one.</b> Every continuous forecast source on this desk is a
 * <em>continuation</em> bet — the EWMAC trend sensor (ADR-0066), the momentum z-score detector, and
 * social sentiment all say "this move will carry on". A range-bound tape is the state in which that
 * whole family is wrong together, and it is a state the desk's own price-derived detector reports
 * routinely (Kaufman efficiency ratios well below the CHOP band across most names). The threshold
 * mean-reversion algo exists, but it is a <em>detector</em>: it is silent until a window move breaches
 * its sigma threshold, so on a quiet tape it contributes nothing at all to the fusion cross-section
 * and accumulates no evidence about itself. This sensor is the continuous counterpart — it has a
 * reading for every name on every cycle, so the fusion layer can hold a calibrated view in the chop
 * regime instead of only in the trend regime, and so the reversion hypothesis is <em>measured</em>
 * rather than assumed.
 *
 * <p><b>It is not the trend sensor negated.</b> That distinction is the whole design. Inverting a
 * losing signal is the classic overfit — it makes the desk a contrarian bet on its own trend model and
 * inherits every one of that model's biases with the sign flipped. This is a different statistic with
 * its own economics: a bounded <em>level</em> reading against the name's own realised extremes (where
 * are we inside the range we have actually traded?), not a smoothed <em>rate-of-change</em> crossover;
 * largest at the edge of the range and zero at its centre, where EWMAC is largest mid-move; and
 * weighted by the opposite regime quality. It earns its allocation on its own measured expectancy or
 * it does not trade — nothing here assumes it is right.
 *
 * <p><b>The computation</b>, per instrument, on each cycle's fresh mark:
 * <pre>
 *   window = the last Nr price steps (Nr+1 prices), the name's own realised range
 *   hi, lo = max, min over the window                    the Donchian channel
 *   pos    = (2·price − hi − lo) / (hi − lo) ∈ [−1,1]    where in its own range we sit
 *   er     = Kaufman efficiency ratio over the window ∈ [0,1] — trend vs chop
 *   q      = −pos × (1 − er)                             fade the stretch, weighted by CHOPPINESS
 *   scale  = EWMA of |q| over span Nn                    what "typical" means ON THIS STREAM
 *   score  = q / scale                                   E|score| ≈ 1 by construction
 * </pre>
 * The leading minus is the fade: at the top of the range ({@code pos → +1}) the sensor is short, at
 * the bottom ({@code pos → −1}) long, and mid-range it has no view. {@code pos} is bounded by
 * construction — the current price is inside the window that produced {@code hi} and {@code lo} — so
 * the reading cannot run away on an outlier the way an unbounded ratio can.
 *
 * <p><b>Why {@code (1 − er)}, and why before normalisation.</b> The edge of a range and the start of a
 * breakout are the same observation; the efficiency ratio is what separates them. As {@code er → 1}
 * (a clean directional move) the weight goes to zero — the sensor refuses to stand in front of a
 * trend, which is the single most expensive way to trade mean reversion. As {@code er → 0} (a name
 * oscillating with no net progress) the fade gets full weight. This is the exact dual of the trend
 * sensor's {@code × er}: the two sensors partition the same quality measure rather than competing for
 * it, so on any given name at most one of them speaks loudly. It is applied <em>before</em>
 * normalisation for the ADR-0066 reason: applied after, it would be a blanket haircut shrinking every
 * forecast toward zero (E[1 − ER] &lt; 1) and quietly changing what the conviction floor means;
 * applied before, it is a <em>discriminator</em> — a stretch in genuinely dead water outranks the same
 * stretch in a name that is going somewhere — while the book's overall scale stays the house
 * convention.
 *
 * <p><b>The scale estimator is warmed before it is trusted</b>, for the reason ADR-0066 paid for: it
 * is the denominator of every reading, and an EWMA seeded from a single observation is an arbitrary
 * anchor, not a calibration. A name whose first reading lands in a quiet patch would otherwise report
 * every ordinary stretch afterwards as an extreme one and pin at the forecast cap — maximum conviction
 * at the moment the sensor knows least. So the first {@code Nn/2} readings accumulate into a running
 * mean of {@code |q|}, the sensor publishes <b>no view</b> (score 0, not warm) until it has them, and
 * only then does it switch to exponential updating.
 *
 * <p><b>Self-calibration is what makes it feed-agnostic</b> (invariant 9): the range is the
 * instrument's own realised range, the quality weight is a ratio of its own steps, and the output is
 * in units of its own typical reading. Nothing here is a price level, a bps constant or an asset-class
 * assumption, so the same sensor reads a simulated equity, a real FX cross and a futures contract on
 * the same scale and adapts as that scale moves.
 *
 * <p>Exact decimal on prices (invariant 1): the window, the channel and the position numerator and
 * denominator are all {@link BigDecimal}; only the dimensionless ratios become {@code double}, as they
 * do for the existing z-score and EWMAC sources. The output is a conviction, never a size, price or
 * PnL number (ADR-0016 / invariant 7) — the deterministic fusion layer downstream decides whether it
 * becomes a position, and the edge gate, conviction floor, backtest veto and the deterministic floor
 * all still sit between this reading and a fill. Not thread-safe: updated and read from a single
 * scheduled evaluation thread, like {@link EwmacTrendForecaster} and {@link TrendDetector}.
 */
public final class RangeReversionForecaster {

    private static final MathContext MC = MathContext.DECIMAL64;

    /**
     * Lookback lengths, in evaluation cycles. Both are <b>shape</b> dials — window lengths, not money,
     * risk or exposure numbers: neither sizes anything, and the output is normalised to the same
     * expected magnitude whatever they are.
     *
     * @param rangeSpan         price steps in the Donchian/efficiency-ratio window — the horizon over
     *                          which "stretched" is defined
     * @param normalisationSpan span of the EWMA of |quality-weighted stretch| that sets "typical" —
     *                          long relative to {@code rangeSpan} so the scale is a property of the
     *                          stream rather than of the stretch currently being measured
     */
    public record Params(int rangeSpan, int normalisationSpan) {
        public Params {
            if (rangeSpan < 2) {
                rangeSpan = 2; // a range needs at least one step to have a high and a low
            }
            if (normalisationSpan < rangeSpan) {
                normalisationSpan = rangeSpan;
            }
        }
    }

    /**
     * One instrument's reading — {@code score} plus the parts it was built from, for the UI/tests.
     * {@code warm} means the sensor is <b>speaking</b>: both the range window and the scale estimator
     * have their history, so {@code score} is a calibrated reading rather than a placeholder zero.
     * {@code rangePosition} is the raw {@code pos} above (positive = near the top of its range).
     */
    public record Reading(String instrumentId, double score, double rangePosition, double efficiencyRatio,
                          boolean warm) {
        static Reading cold(String instrumentId) {
            return new Reading(instrumentId, 0.0, 0.0, 0.0, false);
        }
    }

    private static final class State {
        private final Deque<BigDecimal> window = new ArrayDeque<>();
        private double absScale;     // EWMA of |quality-weighted stretch| (running mean while warming)
        private double absScaleSum;  // sum of |q| over the warm-up readings, for that running mean
        private int scaleSamples;    // how many |q| readings the scale estimator has absorbed
    }

    private final Params params;
    private final double alphaScale;
    /** Readings the scale estimator must absorb before the sensor speaks — see the class doc. */
    private final int scaleWarmupSamples;
    private final Map<String, State> states = new LinkedHashMap<>();
    private final Map<String, Reading> readings = new LinkedHashMap<>();

    public RangeReversionForecaster(Params params) {
        this.params = params;
        this.alphaScale = 2.0 / (params.normalisationSpan() + 1.0);
        // Half the normalisation span: derived from the configured span rather than a new dial, so
        // there is one place to tune "how long is 'typical' measured over". A shape/warm-up length,
        // not a money, risk or exposure number — it delays the first reading, it never sizes anything.
        this.scaleWarmupSamples = Math.max(1, params.normalisationSpan() / 2);
    }

    /**
     * Feeds one fresh price and returns this instrument's current reading. A stale or non-positive
     * price must not be passed — a repeated stale mark would fake a zero-return step and bias both the
     * range and the efficiency ratio toward "no movement" (the same rule {@link TrendDetector} and
     * {@link EwmacTrendForecaster} follow).
     *
     * @return the reading; {@code score} is 0 (no view) until the window and the scale estimator are full
     */
    public Reading update(String instrumentId, BigDecimal price) {
        if (instrumentId == null || price == null || price.signum() <= 0) {
            return Reading.cold(instrumentId);
        }
        State s = states.computeIfAbsent(instrumentId, k -> new State());
        s.window.addLast(price);
        while (s.window.size() > params.rangeSpan() + 1) {
            s.window.removeFirst();
        }
        if (s.window.size() < params.rangeSpan() + 1) {
            return remember(Reading.cold(instrumentId)); // the range is not yet a range
        }

        BigDecimal hi = null;
        BigDecimal lo = null;
        for (BigDecimal p : s.window) {
            if (hi == null || p.compareTo(hi) > 0) {
                hi = p;
            }
            if (lo == null || p.compareTo(lo) < 0) {
                lo = p;
            }
        }
        BigDecimal span = hi.subtract(lo);
        if (span.signum() <= 0) {
            // A dead-flat window has no range to be stretched against — no view, and nothing to
            // measure "typical" with either, so the scale estimator is deliberately not advanced.
            return remember(new Reading(instrumentId, 0.0, 0.0, 0.0, false));
        }
        // pos = (price − mid)/half, written as one exact division: mid and half both carry a /2 that
        // cancels, so the channel midpoint never needs a rounded intermediate.
        double pos = price.add(price).subtract(hi).subtract(lo).divide(span, MC).doubleValue();
        double er = TrendDetector.efficiencyRatio(new ArrayList<>(s.window)).doubleValue();
        double q = -pos * (1.0 - er);
        if (!Double.isFinite(q)) {
            return remember(Reading.cold(instrumentId));
        }

        double absQ = Math.abs(q);
        s.scaleSamples++;
        if (s.scaleSamples <= scaleWarmupSamples) {
            // Still measuring what "typical" is on this stream: hold the estimate as the running mean
            // of the readings so far and report no view (ADR-0066's lesson, inherited deliberately).
            s.absScaleSum += absQ;
            s.absScale = s.absScaleSum / s.scaleSamples;
            return remember(new Reading(instrumentId, 0.0, pos, er, false));
        }
        s.absScale += alphaScale * (absQ - s.absScale);
        if (!(s.absScale > 0)) {
            return remember(new Reading(instrumentId, 0.0, pos, er, true));
        }
        double score = q / s.absScale;
        return remember(new Reading(instrumentId, Double.isFinite(score) ? score : 0.0, pos, er, true));
    }

    private Reading remember(Reading reading) {
        readings.put(reading.instrumentId(), reading);
        return reading;
    }

    /** The last reading for an instrument, or a cold one if it has never been seen. */
    public Reading readingFor(String instrumentId) {
        return readings.getOrDefault(instrumentId, Reading.cold(instrumentId));
    }

    /** Snapshot of every instrument's latest reading (for the API/UI). */
    public Map<String, Reading> readings() {
        return Map.copyOf(readings);
    }
}
