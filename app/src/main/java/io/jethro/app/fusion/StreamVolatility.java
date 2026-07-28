package io.jethro.app.fusion;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * Per-name return volatility measured from the MARK STREAM (ADR-0086) — the risk sensor the desk needs
 * for names its daily-close estimate does not cover.
 *
 * <p><b>Why a second volatility estimator.</b> Every σ the desk currently prices risk with —
 * {@link VolatilityBudget}'s per-name budget split, {@link PortfolioRiskNormaliser}'s correlation
 * control, the parametric VaR, the ADR-0038 hedge advisor — is computed from the {@code daily_close}
 * series, and an instrument only enters that estimate once it has accumulated enough admissible
 * consecutive sessions in the running feed mode (ADR-0073). That is the right basis for a daily risk
 * number and the wrong one for a live one: on the current book it covers 3 of the 23 names the fusion
 * layer plans, and it covers <em>none</em> of the names the desk actually holds. A risk control keyed on
 * it is therefore silent exactly where it is needed. The mark stream has no such gap — it is the same
 * ~1&nbsp;Hz series every forecast sensor already consumes, warm for every name in the universe.
 *
 * <p><b>What it measures.</b> Sampled at a FIXED cadence (the caller's evaluation interval, so the
 * estimate is comparable across names and across restarts), the EWMA of squared log returns:
 * <pre>
 *   rᵢ  = ln(pᵢ / pᵢ₋₁)                       one interval's log return
 *   v   ← (1−α)·v + α·rᵢ²        α = 2/(span+1)   EWMA variance of an interval return
 *   σ_Δ = √v                                  σ of ONE sampling interval
 *   σ_h = σ_Δ · √(h / Δ)                      σ over a holding horizon of h seconds
 * </pre>
 * The √time scaling is the ordinary i.i.d.-increment convention (Hull, <i>Options, Futures and Other
 * Derivatives</i>, ch. 15) — it is exact for a random walk and is the same assumption the desk's daily
 * σ already makes when it is compared with anything measured over a different period. It is stated here
 * rather than buried because it is the one modelling assumption in this class.
 *
 * <p><b>Warm-up.</b> Like both forecast sensors, the scale estimator runs as a plain running mean until
 * it has absorbed {@code span} returns and only then decays (ADR-0066's lesson: dividing by a
 * one-observation anchor is worse than staying silent), and it reports nothing at all until then. On a
 * desk redeployed every half hour that warm-up exceeds the process lifetime, so callers are expected to
 * seed it from the durable mark history through {@link SensorWarmup} exactly as the sensors do
 * (ADR-0071) — otherwise this class would be permanently cold and the control above it dead code.
 *
 * <p>Nothing here is money, a size or a price: σ is a dimensionless statistic and leaves as a
 * {@code double} at exactly the boundary {@link ReturnCovarianceSource} already draws (invariant 1 —
 * prices arrive as exact decimal and only the ratio becomes a double). It sizes nothing and gates
 * nothing on its own (ADR-0016 / invariant 7). Not thread-safe: confined to one caller's tick thread.
 */
public final class StreamVolatility {

    /** Enough precision for a price ratio; the result is a dimensionless statistic, never money. */
    private static final MathContext RATIO = MathContext.DECIMAL64;

    /**
     * The EWMA span, in samples — a statistical convention, not a money, risk or exposure number. It
     * sets how much history the σ estimate remembers and, with it, how many samples must be absorbed
     * before the estimate speaks at all.
     */
    public record Params(int span) {
        public Params {
            if (span < 2) {
                span = 2; // a variance needs at least two returns to mean anything
            }
        }

        /** The standard EWMA smoothing factor for a span of {@code span} observations. */
        public double alpha() {
            return 2.0 / (span + 1.0);
        }
    }

    private static final class State {
        private BigDecimal lastPrice;
        private double variance;    // EWMA of r² once warm; running mean of r² while warming
        private double varianceSum; // Σr² over the warm-up returns, for that running mean
        private long returns;       // how many returns have been absorbed
    }

    private final Params params;
    private final Map<String, State> states = new HashMap<>();

    public StreamVolatility(Params params) {
        this.params = params == null ? new Params(120) : params;
    }

    /** Returns that must be absorbed before a name's σ is allowed to speak. */
    public int warmupSamples() {
        return params.span();
    }

    /**
     * Absorb one sample of this instrument's price, taken at the caller's fixed cadence. A missing or
     * non-positive price is ignored entirely — it advances nothing, because a fabricated zero return
     * would bias the estimate toward "this name does not move", which is the dangerous direction for a
     * control that decides when to cut.
     */
    public void update(String instrumentId, BigDecimal price) {
        if (instrumentId == null || price == null || price.signum() <= 0) {
            return;
        }
        State s = states.computeIfAbsent(instrumentId, k -> new State());
        BigDecimal previous = s.lastPrice;
        s.lastPrice = price;
        if (previous == null || previous.signum() <= 0) {
            return; // first sight: a price, not yet a return
        }
        double r = Math.log(price.divide(previous, RATIO).doubleValue());
        if (!Double.isFinite(r)) {
            return;
        }
        double r2 = r * r;
        s.returns++;
        if (s.returns <= params.span()) {
            s.varianceSum += r2;
            s.variance = s.varianceSum / s.returns; // running mean while warming — never a 1-sample anchor
        } else {
            double a = params.alpha();
            s.variance = (1.0 - a) * s.variance + a * r2;
        }
    }

    /** σ of one sampling-interval log return; empty while warming or with no dispersion measured yet. */
    public OptionalDouble sigmaPerSample(String instrumentId) {
        State s = instrumentId == null ? null : states.get(instrumentId);
        if (s == null || s.returns < params.span() || !(s.variance > 0) || !Double.isFinite(s.variance)) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(Math.sqrt(s.variance));
    }

    /**
     * σ of this name's return over {@code horizonSeconds}, from samples taken every
     * {@code intervalSeconds}: {@code σ_Δ · √(h/Δ)}. Empty when the name is not measured yet — and the
     * caller must then make no claim about it, exactly as {@link ReturnCovarianceSource} requires
     * (ADR-0016 / invariant 7).
     */
    public OptionalDouble sigmaOver(String instrumentId, long horizonSeconds, long intervalSeconds) {
        OptionalDouble perSample = sigmaPerSample(instrumentId);
        if (perSample.isEmpty() || horizonSeconds <= 0 || intervalSeconds <= 0) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(perSample.getAsDouble()
                * Math.sqrt((double) horizonSeconds / (double) intervalSeconds));
    }

    /** How many names currently carry a usable σ — disclosure for the operator, never an input. */
    public int measuredNames() {
        int n = 0;
        for (String id : states.keySet()) {
            if (sigmaPerSample(id).isPresent()) {
                n++;
            }
        }
        return n;
    }

    /** True once this name has absorbed a return — used to decide whether it still needs seeding. */
    public boolean seen(String instrumentId) {
        return instrumentId != null && states.containsKey(instrumentId);
    }
}
