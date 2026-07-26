package io.jethro.trading.algo.strategy;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A continuous, self-calibrating <b>trend sensor</b>: how strongly is this name trending right now,
 * in units of its own recent behaviour? (ADR-0066.)
 *
 * <p><b>Why a crossover and not another breakout z-score.</b> The desk's existing deterministic sources
 * are threshold detectors — they fire when a short window's move is statistically unusual, and are
 * silent otherwise. A threshold detector answers "did something just happen?"; it cannot answer "are we
 * in a trend, and how much of it should we own?", which is the question a risk-managed trend book has
 * to answer on every cycle for every name. The exponentially-weighted moving-average crossover (EWMAC)
 * is the standard continuous answer (Carver, <i>Systematic Trading</i>, 2015; the time-series-momentum
 * evidence behind it: Moskowitz, Ooi &amp; Pedersen, <i>JFE</i> 2012; Hurst, Ooi &amp; Pedersen,
 * "A Century of Evidence on Trend-Following Investing", <i>JPM</i> 2017). It is also intrinsically
 * asymmetric in the way the owner's thesis asks for: as a trend rolls over the crossover shrinks and
 * then flips, so a position is cut by the signal itself rather than by a separate stop.
 *
 * <p><b>The computation</b>, per instrument, on each cycle's fresh mark:
 * <pre>
 *   fast, slow  = EWMAs of price with spans Nf, Ns                (Ns = 4·Nf, Carver's ratio)
 *   vol         = EWMA of |Δprice| over span Ns                   the name's own step scale
 *   raw         = (fast − slow) / vol                             dimensionless: trend in vol units
 *   er          = Kaufman efficiency ratio over the last Ns steps ∈ [0,1] — trend vs chop
 *   q           = raw × er                                        quality-weighted trend
 *   scale       = EWMA of |q| over span Nn                        what "typical" means ON THIS STREAM
 *   score       = q / scale                                       E|score| ≈ 1 by construction
 * </pre>
 *
 * <p><b>Self-calibration is the point</b> (and what makes it feed-agnostic — invariant 9). Every step
 * is expressed relative to the instrument's own measured behaviour: the crossover in units of its own
 * step vol, the result in units of its own typical reading. Nothing is a price level, a bps constant,
 * or an asset-class assumption, so the same sensor reads a simulated equity, a real FX cross and a
 * futures contract on the same scale, and adapts as that scale moves. The caller maps {@code score}
 * onto the desk's forecast convention by multiplying by the target absolute forecast — so a typical
 * trend reads as a typical conviction and the existing sizing dials keep their meaning.
 *
 * <p>The efficiency ratio enters <em>before</em> normalisation deliberately. Applied afterwards it
 * would be a blanket haircut that shrinks every forecast toward zero (E[ER] &lt; 1) and quietly changes
 * what the conviction floor means. Applied before, it is what it should be — a <em>discriminator</em>:
 * a clean trend outranks a noisy one of the same size, both in the cross-section and over time, while
 * the book's overall scale stays the house convention.
 *
 * <p>Exact decimal on prices (invariant 1): every price, EWMA and vol figure is {@link BigDecimal};
 * only the dimensionless ratios become {@code double}, as they do for the existing z-score sources.
 * The output is a conviction, never a size, price or PnL number (ADR-0016 / invariant 7) — the
 * deterministic fusion layer downstream turns it into a target position. Not thread-safe: updated and
 * read from a single scheduled evaluation thread, like {@link TrendDetector}.
 */
public final class EwmacTrendForecaster {

    private static final MathContext MC = MathContext.DECIMAL64;

    /**
     * Lookback lengths, in evaluation cycles. All three are <b>shape</b> dials — window lengths, not
     * money, risk or exposure numbers: none of them sizes anything, and the output is normalised to
     * the same expected magnitude whatever they are.
     *
     * @param fastSpan          fast EWMA span; Carver's standard variations use slow = 4 × fast
     * @param slowSpan          slow EWMA span; also the step-vol and efficiency-ratio window
     * @param normalisationSpan span of the EWMA of |quality-weighted trend| that sets "typical" —
     *                          long relative to {@code slowSpan} so the scale is a property of the
     *                          stream rather than of the current trend
     */
    public record Params(int fastSpan, int slowSpan, int normalisationSpan) {
        public Params {
            if (fastSpan < 2) {
                fastSpan = 2;
            }
            if (slowSpan <= fastSpan) {
                slowSpan = fastSpan * 4; // a crossover needs the two spans to differ
            }
            if (normalisationSpan < slowSpan) {
                normalisationSpan = slowSpan;
            }
        }
    }

    /** One instrument's reading — {@code score} plus the parts it was built from, for the UI/tests. */
    public record Reading(String instrumentId, double score, double rawTrend, double efficiencyRatio,
                          boolean warm) {
        static Reading cold(String instrumentId) {
            return new Reading(instrumentId, 0.0, 0.0, 0.0, false);
        }
    }

    private static final class State {
        private BigDecimal fast;
        private BigDecimal slow;
        private BigDecimal vol;      // EWMA of |Δprice|
        private BigDecimal last;
        private final Deque<BigDecimal> window = new ArrayDeque<>();
        private double absScale;     // EWMA of |quality-weighted trend|
        private long steps;
    }

    private final Params params;
    private final BigDecimal alphaFast;
    private final BigDecimal alphaSlow;
    private final double alphaScale;
    private final Map<String, State> states = new LinkedHashMap<>();
    private final Map<String, Reading> readings = new LinkedHashMap<>();

    public EwmacTrendForecaster(Params params) {
        this.params = params;
        this.alphaFast = alpha(params.fastSpan());
        this.alphaSlow = alpha(params.slowSpan());
        this.alphaScale = 2.0 / (params.normalisationSpan() + 1.0);
    }

    /** Standard EWMA smoothing constant for a span: {@code α = 2/(span+1)}. */
    private static BigDecimal alpha(int span) {
        return BigDecimal.valueOf(2).divide(BigDecimal.valueOf(span + 1L), MC);
    }

    /**
     * Feeds one fresh price and returns this instrument's current reading. A stale or non-positive
     * price must not be passed — a repeated stale mark would fake a zero-return step and bias the vol
     * and efficiency-ratio windows toward "no movement" (the same rule {@link TrendDetector} follows).
     *
     * @return the reading; {@code score} is 0 (no view) until the windows are full
     */
    public Reading update(String instrumentId, BigDecimal price) {
        if (instrumentId == null || price == null || price.signum() <= 0) {
            return Reading.cold(instrumentId);
        }
        State s = states.computeIfAbsent(instrumentId, k -> new State());
        if (s.last == null) {
            s.fast = price;
            s.slow = price;
            s.last = price;
            s.window.addLast(price);
            return remember(Reading.cold(instrumentId));
        }
        BigDecimal step = price.subtract(s.last).abs();
        s.vol = s.vol == null ? step : ewma(s.vol, step, alphaSlow);
        s.fast = ewma(s.fast, price, alphaFast);
        s.slow = ewma(s.slow, price, alphaSlow);
        s.last = price;
        s.window.addLast(price);
        while (s.window.size() > params.slowSpan() + 1) {
            s.window.removeFirst();
        }
        s.steps++;

        // Warm-up: the slow EWMA must have seen its span, the efficiency-ratio window must be full,
        // and the step vol must be positive — otherwise there is no scale to measure the trend in.
        if (s.steps < params.slowSpan() || s.window.size() < params.slowSpan() + 1
                || s.vol == null || s.vol.signum() <= 0) {
            return remember(Reading.cold(instrumentId));
        }
        double raw = s.fast.subtract(s.slow).divide(s.vol, MC).doubleValue();
        double er = TrendDetector.efficiencyRatio(new ArrayList<>(s.window)).doubleValue();
        double q = raw * er;
        if (!Double.isFinite(q)) {
            return remember(Reading.cold(instrumentId));
        }
        double absQ = Math.abs(q);
        s.absScale = s.absScale == 0.0 ? absQ : s.absScale + alphaScale * (absQ - s.absScale);
        if (!(s.absScale > 0)) {
            return remember(new Reading(instrumentId, 0.0, raw, er, true));
        }
        double score = q / s.absScale;
        return remember(new Reading(instrumentId, Double.isFinite(score) ? score : 0.0, raw, er, true));
    }

    /** {@code ewma ← ewma + α(x − ewma)}, in exact decimal. */
    private static BigDecimal ewma(BigDecimal current, BigDecimal x, BigDecimal alpha) {
        return current.add(x.subtract(current).multiply(alpha, MC), MC);
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
