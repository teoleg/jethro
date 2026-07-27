package io.jethro.app.fusion;

import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Averages a name's combined forecast over the horizon its edge is measured on (ADR-0088) — the signal
 * half of the ADR-0080 identity.
 *
 * <p><b>The defect this closes.</b> ADR-0080 set the desk's <em>position</em> time constant equal to the
 * horizon its sources' expectancy is measured over, so one round trip is paid per horizon of credited
 * return. It said nothing about the <em>signal</em>. A forecast that reverses far faster than that
 * horizon therefore drags the position after it: the target flips sign, the ADR-0080 asymmetry trades the
 * risk-reducing leg in full immediately, and the desk pays a round trip for a view it never held long
 * enough to be graded on. The measured symptom on this book was a name traded tens of times in a window
 * for a net position change a fraction of the notional churned.
 *
 * <p><b>The rule.</b> One first-order (EWMA) filter per instrument on the COMBINED forecast, with time
 * constant equal to the same measured horizon {@code h}:
 * <pre>
 *   α  = 1 − exp(−Δt / h)          // Δt = seconds since this name was last smoothed
 *   f̄ ← f̄ + α · (f − f̄)          // seeded with the first reading
 * </pre>
 * This is {@link TargetPlanner#adjustmentRateFor} applied to the signal instead of the trade: a step
 * response e-folds in exactly {@code h}, so the conviction the desk sizes on is the average of its view
 * over the period that view was graded over. Nothing is chosen — {@code h} is the rung
 * {@link HorizonLadder} selected from the evidence, and Δt is measured, not the nominal cadence, so an
 * irregular tick, a missed cycle or a name that drops out of the cross-section and returns all behave
 * correctly (a long gap drives α → 1, which re-seeds on the current reading rather than resuming a stale
 * average).
 *
 * <p><b>What it does and does not change.</b> A genuinely persistent view is untouched: a constant
 * forecast is its own average, so a name in a sustained move sizes exactly as it does today. An
 * oscillating view is cut down towards its mean — a square wave of amplitude {@code A} and half-period
 * {@code T} settles at {@code A·tanh(T/2h)} — so the desk stops paying spread for a signal that reverses
 * faster than it can be graded, and the ADR-0059 conviction floor gets a value it can actually bite on.
 * Nothing here can create conviction: {@code |f̄| ≤ max|f|} over the window by construction, so the
 * filter can only ever ask for a smaller book than the raw forecast's peak.
 *
 * <p><b>Dimensionless, by the same rule as everything else at this layer.</b> A forecast is a conviction,
 * never a size, a price or a number that enters PnL/risk (ADR-0016 / invariant 7) — it is carried as a
 * {@code double} exactly as {@code combinedForecast}, the diversification multiplier and the
 * ADR-0080 adjustment rate already are. The money boundary is downstream in
 * {@link TargetPlanner#targetQuantity}, which is exact decimal and unchanged.
 *
 * <p>Thread-safety: state is a {@link ConcurrentHashMap} keyed by instrument, but it is written only
 * from the fusion loop's scheduled tick; the map is concurrent so the operator read path
 * ({@link #trackedNames()}) is safe.
 */
public final class ForecastSmoother {

    /** How a planning pass filters a combined forecast; {@link #NONE} leaves the book byte-identical. */
    @FunctionalInterface
    public interface Smoothing {
        double apply(String instrument, double combinedForecast);
    }

    /** The identity filter — what the desk did before ADR-0088, and what an unwired loop uses. */
    public static final Smoothing NONE = (instrument, combinedForecast) -> combinedForecast;

    private record State(double value, long atMillis) {
    }

    private final Map<String, State> states = new ConcurrentHashMap<>();

    /**
     * The EWMA coefficient for a gap of {@code elapsedSeconds} at time constant {@code horizonSeconds}:
     * {@code 1 − exp(−Δt/h)}, the same identity {@link TargetPlanner#adjustmentRateFor} derives the
     * holding period from. Dimensionless — a fraction of a gap, not money.
     *
     * <p>Bounded to {@code (0, 1]}: a non-positive or non-finite horizon, or a gap long relative to it,
     * gives 1, which is "no memory" — the current reading stands, exactly the pre-ADR-0088 behaviour.
     * That is the safe degenerate direction: this filter can never freeze the desk on a stale view.
     */
    public static double alphaFor(double elapsedSeconds, double horizonSeconds) {
        if (!Double.isFinite(elapsedSeconds) || !Double.isFinite(horizonSeconds) || horizonSeconds <= 0) {
            return 1.0;
        }
        double dt = Math.max(0.0, elapsedSeconds);
        double a = 1.0 - Math.exp(-dt / horizonSeconds);
        if (!Double.isFinite(a)) {
            return 1.0;
        }
        return Math.max(0.0, Math.min(1.0, a));
    }

    /**
     * This name's horizon-averaged conviction, advancing its filter to {@code nowMillis}.
     *
     * <p>First sight of a name seeds the average at the reading itself (the standard EWMA
     * initialisation), so the desk behaves exactly as it does today on the cycle after a restart and the
     * average builds from there — a filter that started at zero would leave a freshly-deployed desk
     * unable to hold a view for one whole horizon, which is a worse failure than a slightly quick first
     * step (the ADR-0071 lesson: a control that cannot warm is a control that does not exist).
     *
     * @param instrument      the name being planned
     * @param combinedForecast this cycle's fused, capped conviction
     * @param nowMillis       the instant this planning pass reads the book at
     * @param horizonSeconds  the measurement horizon the evidence selected (ADR-0082); ≤ 0 disables
     *                        memory and returns the raw reading
     */
    public double smooth(String instrument, double combinedForecast, long nowMillis, long horizonSeconds) {
        if (instrument == null || !Double.isFinite(combinedForecast)) {
            return 0.0;
        }
        State prior = states.get(instrument);
        double smoothed;
        if (prior == null || nowMillis <= prior.atMillis()) {
            // No history, or a clock that did not advance: nothing to average over yet.
            smoothed = combinedForecast;
        } else {
            double elapsedSeconds = (nowMillis - prior.atMillis()) / 1_000.0;
            double alpha = alphaFor(elapsedSeconds, horizonSeconds);
            smoothed = prior.value() + alpha * (combinedForecast - prior.value());
        }
        // Stays inside the Carver band by construction (a convex combination of two capped values), but
        // clamp anyway so this can never be the thing that hands the sizer an out-of-range conviction.
        smoothed = Forecast.clamp(smoothed);
        states.put(instrument, new State(smoothed, nowMillis));
        return smoothed;
    }

    /** This name's current averaged conviction, or empty when it has not been planned yet. */
    public OptionalDouble current(String instrument) {
        State s = instrument == null ? null : states.get(instrument);
        return s == null ? OptionalDouble.empty() : OptionalDouble.of(s.value());
    }

    /** How many names carry a running average — the operator's "is this control live" count. */
    public int trackedNames() {
        return states.size();
    }
}
