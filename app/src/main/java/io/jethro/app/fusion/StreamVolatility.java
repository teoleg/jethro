package io.jethro.app.fusion;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
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
 * <p><b>A sample is a PRINT, not a cycle (ADR-0116).</b> The mark cache is a last-value conflation
 * point, so a caller reading it on a fixed cadence sees the same price republished for as long as the
 * tape is quiet. Absorbing those as returns is precisely the "fabricated zero return" this class already
 * refuses for an absent price — it is the same bias, arriving through the door that is actually open —
 * so {@link #update(String, BigDecimal, Instant)} admits a sample only when the market's own clock has
 * advanced, on the {@link PrintClock} rule ADR-0113 established for the forecast sensors. The two-argument
 * form treats every call as a print, which is what a replay of the durable series (one point per distinct
 * print) or a backtest bar already is.
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
    /** ADR-0116: the market's own clock, so a republished mark cannot become a return. */
    private final PrintClock printClock = new PrintClock();

    public StreamVolatility(Params params) {
        this.params = params == null ? new Params(120) : params;
    }

    /** Returns that must be absorbed before a name's σ is allowed to speak. */
    public int warmupSamples() {
        return params.span();
    }

    /**
     * How many stored PRICES a warm-restart replay must hand this sensor to complete that warm-up:
     * {@code warmupSamples() + 1} (ADR-0117).
     *
     * <p>The two counts are not the same number and must not be used interchangeably. This sensor is
     * denominated in <em>returns</em>, and a return needs two prices: {@link #update} says so itself —
     * the first price of any series "is a price, not yet a return" — so replaying N prices yields N−1
     * returns. Seeding with {@code warmupSamples()} prices therefore lands exactly one return short,
     * for every name, at any depth of history, and the sensor stays silent while reporting a full seed
     * ("seeded 120 of 120 … still cold"). Since ADR-0113/0116 the missing return can then only arrive
     * on the tape's own clock, so on a name that prints every twenty minutes the ADR-0071 warm restart
     * hands over a sensor that is still mute for most of a process lifetime — which is the precise
     * failure ADR-0071 exists to prevent.
     *
     * <p>Arithmetic, not a dial: it is the count of returns derivable from a price series, so there is
     * no number here needing provenance (invariant 7 / ADR-0016), and it sizes nothing.
     */
    public int warmupPrices() {
        return warmupSamples() + 1;
    }

    /**
     * Absorb one sample of this instrument's price <b>only if the tape has printed since the last one
     * this sensor consumed</b> (ADR-0116) — the same rule, and the same clock, ADR-0113 applies to the
     * forecast sensors.
     *
     * <p>Without it a quiet tape is read as a still one: the mark cache republishes the last price every
     * cycle, each republish is absorbed as {@code r = ln(p/p) = 0}, and the EWMA variance decays
     * geometrically toward zero while the warm-up counter fills with observations that never happened.
     * The consequence is not a slightly wrong σ, it is a <em>manufactured</em> one, and it lands on the
     * one control that stops a position out: {@link TrailingRiskCut} cuts when the adverse excursion from
     * the peak exceeds {@code k·σ_h}, so a σ decayed to ~0 puts the trigger at ~0 and the first genuine
     * move of the next session cuts the position on noise — every name the desk held across the close,
     * at the reopen, at full spread. It also drives the reverse error while the name is still frozen: the
     * counter reaches {@code span} on nothing, so the sensor starts speaking a number built from silence.
     *
     * @param providerTimestamp the mark's PROVIDER timestamp — the market's clock, never ingest time
     *                          (invariant 5). {@code null} is admitted, per {@link PrintClock}: with no
     *                          clock to judge by, declining to measure must not silence the sensor.
     */
    public void update(String instrumentId, BigDecimal price, Instant providerTimestamp) {
        if (instrumentId == null || price == null || price.signum() <= 0) {
            return; // nothing to consume, and nothing to record against the clock
        }
        if (!printClock.advanced(instrumentId, providerTimestamp)) {
            return; // the tape has not printed since we last looked — there is no return to observe
        }
        update(instrumentId, price);
    }

    /**
     * Absorb one sample of this instrument's price, taken at the caller's fixed cadence, treating the
     * call itself as the print. Correct for a replay of the durable mark series (one point per distinct
     * print, ADR-0071) and for a backtest bar; a caller reading the LIVE mark cache must use
     * {@link #update(String, BigDecimal, Instant)} instead, because that cache republishes.
     *
     * <p>A missing or non-positive price is ignored entirely — it advances nothing, because a fabricated
     * zero return would bias the estimate toward "this name does not move", which is the dangerous
     * direction for a control that decides when to cut.
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

    /**
     * Drops everything this sensor knows about one instrument, so the next {@link #update} treats its
     * price as a first sight and the warm-up restarts from there (ADR-0131 re-seed).
     *
     * <p>{@code lastPrice}, the running variance, its warm-up sum and the return counter are all derived
     * from one price sequence, so the only coherent reset is to drop the state whole — clearing the
     * variance but keeping {@code lastPrice} would manufacture a return across the reset boundary.
     *
     * <p><b>Caller contract: reset a name only while {@link #sigmaPerSample} is empty.</b> This σ is the
     * distance the ADR-0086 trailing cut is measured in; re-deriving a MEASURED name's σ would move a
     * live stop underneath a live position. A name with no σ has no stop to move — which is exactly why
     * ADR-0126 refuses to open it, and exactly why re-seeding it is safe.
     *
     * <p>The print clock is deliberately left alone: it records the last provider timestamp consumed, and
     * forgetting that would let a republished mark be read as a fresh print (ADR-0116).
     */
    public void forget(String instrumentId) {
        if (instrumentId == null) {
            return;
        }
        states.remove(instrumentId);
    }
}
