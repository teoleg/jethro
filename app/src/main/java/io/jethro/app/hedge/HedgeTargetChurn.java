package io.jethro.app.hedge;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How much the hedge target moves between the moments the hedge can act on it (ADR-0098).
 *
 * <p>The advisor's raw target is a function of the book's live exposures, so it moves every time the
 * strategy trades. This estimator measures that movement: an EWMA of the squared <b>step</b> in the
 * raw hedge-target notional, sampled once per hedge <em>cooldown</em> — the interval between one
 * hedge order and the next possible one, which is the horizon over which a target must survive to be
 * worth trading. {@code σ} is the square root, in USD of proxy notional.
 *
 * <p>The decay is {@code λ = 0.94}, the same RiskMetrics decay {@link
 * io.jethro.trading.riskpnl.CovMath} already uses for the firm covariance — reused rather than
 * introduced, so the desk has one memory constant. At the hedge cooldown cadence its effective
 * memory is {@code 1/(1−λ) ≈ 16} samples.
 *
 * <p>The first step <b>seeds</b> the EWMA (rather than decaying from zero), so the estimate is not
 * biased low while warming; σ is published only from the second step, and until then the caller
 * shrinks nothing and the hedge behaves exactly as it did before ADR-0098.
 *
 * <p><b>Directional efficiency (ADR-0100).</b> The same sampled series also answers a second
 * question: of the distance the target travels, how much of it is <em>displacement</em> rather than
 * round trip? That is Kaufman's efficiency ratio in exponential form — {@code E = |D| / A} over an
 * EWMA of the signed step {@code D} and an EWMA of its absolute value {@code A}, both at the same
 * {@code λ}. {@code |D| ≤ A} by the triangle inequality on identical weights, so {@code E ∈ [0,1]}
 * with no clamping: 1 = every step in the same direction (a target that is going somewhere), 0 = a
 * pure oscillation (a target that is only churning). Unlike the variance recursion these two are
 * initialised at <b>zero</b>, not seeded: the warm-up factor {@code (1−λⁿ)} is then identical in
 * numerator and denominator and cancels exactly in the ratio, so E is unbiased from its first
 * reading (n steps of {@code +x} give E = 1 exactly; {@code +x, −x} gives {@code (1−λ)/(1+λ)}).
 *
 * <p>Exact decimal throughout (invariant 1) at 20 significant digits — the recursion multiplies by
 * λ every step, so the working precision must be bounded explicitly or the scale grows without limit.
 * State is keyed per hedge <b>axis</b>, not per proxy instrument: the notional is in USD either way,
 * and the thing being measured is how much that axis's exposure moves — a proxy switch (ADR-0042)
 * must not reset the memory or file the same series under two keys. Thread-safe for the advisor's
 * single writer plus REST readers.
 */
public final class HedgeTargetChurn {

    /** RiskMetrics decay, the same constant as {@link io.jethro.trading.riskpnl.CovMath#LAMBDA}. */
    static final BigDecimal LAMBDA = new BigDecimal("0.94");
    private static final BigDecimal ONE_MINUS_LAMBDA = BigDecimal.ONE.subtract(LAMBDA);
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_EVEN);
    /** Below two steps a σ is one observation of a squared difference — not yet an estimate. */
    private static final int MIN_STEPS = 2;

    private static final class State {
        private BigDecimal last;
        private BigDecimal ewmaVariance = BigDecimal.ZERO;
        /** ADR-0100 — zero-initialised on purpose: the (1−λⁿ) warm-up bias cancels in |drift|/absStep. */
        private BigDecimal ewmaDrift = BigDecimal.ZERO;
        private BigDecimal ewmaAbsStep = BigDecimal.ZERO;
        private int steps;
        private long lastSampleMillis = Long.MIN_VALUE;
    }

    private final long sampleIntervalMillis;
    private final Map<String, State> byAxis = new ConcurrentHashMap<>();

    /**
     * @param sampleIntervalMillis the spacing the series is sampled at — the hedge cooldown, i.e.
     *                             the soonest the desk could act on a changed target. Samples
     *                             offered sooner are ignored, so a busy evaluation loop cannot
     *                             shorten the step and understate σ.
     */
    public HedgeTargetChurn(long sampleIntervalMillis) {
        this.sampleIntervalMillis = Math.max(0, sampleIntervalMillis);
    }

    /**
     * Offer the current <b>raw</b> (pre-shrinkage) hedge target notional on an axis. Ignored when
     * the value is absent or the previous sample is more recent than the sampling interval.
     */
    public void observe(String axis, BigDecimal rawTargetNotionalUsd, long nowMillis) {
        if (axis == null || rawTargetNotionalUsd == null) {
            return;
        }
        State s = byAxis.computeIfAbsent(axis, k -> new State());
        synchronized (s) {
            if (s.lastSampleMillis != Long.MIN_VALUE
                    && nowMillis - s.lastSampleMillis < sampleIntervalMillis) {
                return;
            }
            BigDecimal previous = s.last;
            s.last = rawTargetNotionalUsd;
            s.lastSampleMillis = nowMillis;
            if (previous == null) {
                return; // first sample is a level, not a step
            }
            BigDecimal step = rawTargetNotionalUsd.subtract(previous);
            BigDecimal squared = step.multiply(step, MC);
            s.ewmaVariance = s.steps == 0 ? squared
                    : s.ewmaVariance.multiply(LAMBDA, MC).add(squared.multiply(ONE_MINUS_LAMBDA, MC), MC);
            s.ewmaDrift = s.ewmaDrift.multiply(LAMBDA, MC)
                    .add(step.multiply(ONE_MINUS_LAMBDA, MC), MC);
            s.ewmaAbsStep = s.ewmaAbsStep.multiply(LAMBDA, MC)
                    .add(step.abs().multiply(ONE_MINUS_LAMBDA, MC), MC);
            s.steps++;
        }
    }

    /**
     * σ of the target's step on this axis, in USD, or empty while warming (&lt; 2 steps) or when
     * the target has not moved at all — in both cases the caller must not shrink.
     */
    public Optional<BigDecimal> sigmaUsd(String axis) {
        State s = axis == null ? null : byAxis.get(axis);
        if (s == null) {
            return Optional.empty();
        }
        synchronized (s) {
            if (s.steps < MIN_STEPS || s.ewmaVariance.signum() <= 0) {
                return Optional.empty();
            }
            return Optional.of(s.ewmaVariance.sqrt(MC).setScale(2, RoundingMode.HALF_UP));
        }
    }

    /**
     * Directional efficiency of the target's path on this axis (ADR-0100): the fraction of the
     * distance it travels that is net displacement, {@code E = |EWMA(step)| / EWMA(|step|)} in
     * {@code [0,1]}. Empty while warming (&lt; 2 steps) or when the target has not moved at all —
     * in both cases the caller must not slow the hedge, and it tracks its target as it did before.
     */
    public Optional<BigDecimal> efficiencyRatio(String axis) {
        State s = axis == null ? null : byAxis.get(axis);
        if (s == null) {
            return Optional.empty();
        }
        synchronized (s) {
            if (s.steps < MIN_STEPS || s.ewmaAbsStep.signum() <= 0) {
                return Optional.empty();
            }
            BigDecimal ratio = s.ewmaDrift.abs().divide(s.ewmaAbsStep, 6, RoundingMode.HALF_EVEN);
            // |D| ≤ A holds exactly; the clamp only absorbs the last-digit rounding of the divide.
            return Optional.of(ratio.min(BigDecimal.ONE));
        }
    }
}
