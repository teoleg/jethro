package io.jethro.app.fusion;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The MEASURED forecast scalar per source (ADR-0092) — Carver's forecast-scalar step done on the
 * running stream instead of taken on trust.
 *
 * <p><b>The problem this fixes.</b> Every continuous source hands {@link ForecastRegistry} a reading
 * it <em>claims</em> is already normalised: the trend and reversion sensors promise a self-normalised
 * score whose expected absolute value on the running stream is ≈ 1, the strategy sources promise a
 * z-score whose expected absolute value is the configured {@code expected-abs-z}. The mapper multiplies
 * by {@link Forecast#TARGET_ABS} on the strength of that promise and caps at {@link Forecast#CAP}. When
 * the promise is wrong the cap silently becomes the forecast: a source running at E|reading| = 1.5×
 * its claim spends a large fraction of its cross-section clipped at ±CAP, and every clipped name reads
 * the SAME number. At that point the source is a sign function, not a forecast — the desk sizes a
 * 1.8-sigma name and a 2.4-sigma name identically, and the cross-sectional selection information that
 * justified running 23 names instead of one is gone. It also oversizes: the target is linear in the
 * forecast ({@code target = forecast/TARGET_ABS × unitNotional / unit value}), so an inflated scale is
 * an inflated book and an inflated turnover bill to ramp into it.
 *
 * <p><b>The measurement.</b> One <em>expanding</em> mean of |claim| per source, pooled across
 * instruments, over the readings the source has actually published this session:
 * <pre>
 *   scalar_s = TARGET_ABS / max(TARGET_ABS, mean|claim_s|)
 *   forecast = clamp(claim × scalar_s)
 * </pre>
 * Expanding, not decaying, because the thing being estimated is a structural property of the sensor
 * (how big its readings are), not a market regime — Carver, <i>Systematic Trading</i> (2015) ch. 7,
 * estimates the forecast scalar over an expanding window for exactly that reason, and it means this
 * class carries no half-life or span to invent. The scalar is measured on the <em>claim</em> (the
 * pre-cap input), never on the output, so there is no feedback loop: rescaling cannot move the sample
 * it is estimated from.
 *
 * <p><b>One-way, by choice.</b> The {@code max(TARGET_ABS, …)} makes the scalar ≤ 1: a source that
 * over-delivers is scaled DOWN to its promise, a source that under-delivers is left exactly as it is
 * rather than levered UP on an estimate. A textbook forecast scalar is symmetric; this one is not,
 * because scaling up on a thin or quiet sample multiplies real money into a book, and the desk has
 * already paid for that lesson twice (ADR-0087, ADR-0088 — both reverted for growing exposure with no
 * PnL). When the sensor keeps its promise the scalar is exactly 1.0 and this class is a no-op.
 *
 * <p><b>Warm-up.</b> Below {@code minSamples} readings the scalar is 1.0 — identical to the behaviour
 * before this class existed — so a source is never rescaled on a handful of observations.
 *
 * <p>Everything here is a dimensionless conviction, never a size, price or PnL (ADR-0016 /
 * invariant 7): {@code double} is the right type, exactly as it is for {@link Forecast#value()}. The
 * deterministic layer downstream turns the combined forecast into a target position in exact decimal.
 * Thread-safe — sensors publish from their own lifecycles.
 */
public final class ForecastScalars {

    /** What one source's scaling looks like right now — disclosure for the operator. */
    public record Measurement(String source, long readings, double meanAbsClaim, double scalar) {
    }

    private static final class State {
        private long n;
        private double sumAbs;
    }

    private final boolean enabled;
    private final int minSamples;
    private final Map<String, State> bySource = new ConcurrentHashMap<>();

    /**
     * @param enabled    false → every scalar is 1.0 and the claims pass through untouched
     * @param minSamples readings a source must have published before its measured scale is trusted
     */
    public ForecastScalars(boolean enabled, int minSamples) {
        this.enabled = enabled;
        this.minSamples = Math.max(2, minSamples); // a mean needs at least two observations
    }

    /**
     * Record {@code claim} against {@code source} and return it rescaled to the source's measured
     * scale. A zero or non-finite claim is the "no view" sentinel every mapper returns — not a
     * reading — so it is neither measured nor rescaled.
     */
    public double rescale(String source, double claim) {
        if (!Double.isFinite(claim) || claim == 0.0 || source == null) {
            return 0.0;
        }
        if (!enabled) {
            return claim;
        }
        State st = bySource.computeIfAbsent(source, k -> new State());
        double scalar;
        synchronized (st) {
            // Causal: the scalar applied to this reading is estimated from the readings BEFORE it, then
            // this one joins the sample. A reading must not shrink its own scalar — otherwise the
            // forecast for a name would depend on where in the cycle's cross-section it happened to be
            // published, and an extreme call would be penalised twice for being extreme.
            scalar = scalarOf(st);
            st.n++;
            st.sumAbs += Math.abs(claim);
        }
        return claim * scalar;
    }

    /** The scalar a source is currently being rescaled by (1.0 = untouched). */
    public double scalarFor(String source) {
        State st = bySource.get(source);
        if (st == null || !enabled) {
            return 1.0;
        }
        synchronized (st) {
            return scalarOf(st);
        }
    }

    /** Per-source measurement, for the operator view. Sorted by source for a stable rendering. */
    public Map<String, Measurement> snapshot() {
        Map<String, Measurement> out = new LinkedHashMap<>();
        bySource.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            State st = e.getValue();
            synchronized (st) {
                double mean = st.n == 0 ? 0.0 : st.sumAbs / st.n;
                out.put(e.getKey(), new Measurement(e.getKey(), st.n, mean, scalarOf(st)));
            }
        });
        return out;
    }

    /** Caller holds the state's monitor. */
    private double scalarOf(State st) {
        if (st.n < minSamples) {
            return 1.0; // warming — behave exactly as the un-measured mapper did
        }
        double meanAbs = st.sumAbs / st.n;
        if (!(meanAbs > Forecast.TARGET_ABS)) {
            return 1.0; // the source keeps its promise (or under-delivers) — never lever it up
        }
        return Forecast.TARGET_ABS / meanAbs;
    }
}
